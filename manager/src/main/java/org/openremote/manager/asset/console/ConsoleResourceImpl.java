/*
 * Copyright 2017, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.manager.asset.console;

import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.event.ClientEventService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebResource;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetEvent;
import org.openremote.model.asset.UserAsset;
import org.openremote.model.asset.impl.ConsoleAsset;
import org.openremote.model.asset.impl.GroupAsset;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.console.ConsoleProviders;
import org.openremote.model.console.ConsoleRegistration;
import org.openremote.model.console.ConsoleResource;
import org.openremote.model.http.RequestParams;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.filter.AttributePredicate;
import org.openremote.model.query.filter.ParentPredicate;
import org.openremote.model.query.filter.StringPredicate;
import org.openremote.model.query.filter.TenantPredicate;
import org.openremote.model.security.Tenant;
import org.openremote.model.util.AssetModelUtil;
import org.openremote.model.util.TextUtil;

import javax.validation.ConstraintViolation;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

import static org.openremote.container.concurrent.GlobalLock.withLockReturning;
import static org.openremote.model.value.MetaItemType.ACCESS_PUBLIC_WRITE;
import static org.openremote.model.value.MetaItemType.ACCESS_RESTRICTED_WRITE;

public class ConsoleResourceImpl extends ManagerWebResource implements ConsoleResource {

    public static final String CONSOLE_PARENT_ASSET_NAME = "Consoles";
    protected Map<String, String> realmConsoleParentMap = new HashMap<>();
    protected AssetStorageService assetStorageService;

    public ConsoleResourceImpl(TimerService timerService, ManagerIdentityService identityService, AssetStorageService assetStorageService, ClientEventService clientEventService) {
        super(timerService, identityService);
        this.assetStorageService = assetStorageService;

        // Subscribe for asset events
        clientEventService.addInternalSubscription(
            AssetEvent.class,
            null,
            this::onAssetChange);
    }

    protected void onAssetChange(AssetEvent event) {
        // Remove any parent console asset mapping if the asset gets deleted
        if (event.getCause() == AssetEvent.Cause.DELETE) {
            realmConsoleParentMap.values().remove(event.getAssetId());
        }
    }

    @Override
    public ConsoleRegistration register(RequestParams requestParams, ConsoleRegistration consoleRegistration) {

        if (getRequestTenant() == null) {
            throw new BadRequestException("Invalid realm");
        }

        // Validate the console registration
        if (consoleRegistration == null) {
            throw new BadRequestException(Response.status(Response.Status.BAD_REQUEST).build());
        }

        ConstraintViolation<?>[] constraintViolations = AssetModelUtil.validate(consoleRegistration);

        if (constraintViolations.length > 0) {
            throw new BadRequestException(Response.status(Response.Status.BAD_REQUEST).entity(constraintViolations).build());
        }

        ConsoleAsset consoleAsset = null;

        // If console registration has an id and asset exists then ensure asset type is console
        if (!TextUtil.isNullOrEmpty(consoleRegistration.getId())) {
            consoleAsset = assetStorageService.find(consoleRegistration.getId(), true, ConsoleAsset.class);
        }

        if (consoleAsset == null) {
            consoleAsset = initConsoleAsset(consoleRegistration, true, true);
            consoleAsset.setRealm(getRequestRealm());
            consoleAsset.setParentId(getConsoleParentAssetId(getRequestRealm()));
            consoleAsset.setId(consoleRegistration.getId());
        }

        consoleAsset.setConsoleName(consoleRegistration.getName())
            .setConsoleVersion(consoleRegistration.getVersion())
            .setConsoleProviders(new ConsoleProviders(consoleRegistration.getProviders()))
            .setConsolePlatform(consoleRegistration.getPlatform());

        consoleAsset = assetStorageService.merge(consoleAsset);
        consoleRegistration.setId(consoleAsset.getId());

        // If authenticated link the console to this user
        if (isAuthenticated()) {
            assetStorageService.storeUserAsset(new UserAsset(getAuthenticatedRealm(), getUserId(), consoleAsset.getId()));
        }

        return consoleRegistration;
    }

    public static ConsoleAsset initConsoleAsset(ConsoleRegistration consoleRegistration, boolean allowPublicLocationWrite, boolean allowRestrictedLocationWrite) {
        ConsoleAsset consoleAsset = new ConsoleAsset(consoleRegistration.getName());

        if (allowPublicLocationWrite) {
            consoleAsset.getAttributes().getOrCreate(Asset.LOCATION).addOrReplaceMeta(new MetaItem<>(ACCESS_PUBLIC_WRITE, true));
        }
        if (allowRestrictedLocationWrite) {
            consoleAsset.getAttributes().getOrCreate(Asset.LOCATION).addOrReplaceMeta(new MetaItem<>(ACCESS_RESTRICTED_WRITE, true));
        }

        return consoleAsset;
    }

    public String getConsoleParentAssetId(String realm) {
        return withLockReturning(getClass().getSimpleName() + "::getConsoleParentAssetId", () -> {
            String id = realmConsoleParentMap.get(realm);

            if (TextUtil.isNullOrEmpty(id)) {
                Asset<?> consoleParent = getConsoleParentAsset(assetStorageService, getRequestTenant());
                id = consoleParent.getId();
                realmConsoleParentMap.put(realm, id);
            }

            return id;
        });
    }

    public static Asset<?> getConsoleParentAsset(AssetStorageService assetStorageService, Tenant tenant) {

        // Look for a group asset with a child type of console in the realm root
        GroupAsset consoleParent = (GroupAsset) assetStorageService.find(
            new AssetQuery()
                .select(AssetQuery.Select.selectExcludeAll())
                .names(CONSOLE_PARENT_ASSET_NAME)
                .parents(new ParentPredicate(true))
                .types(GroupAsset.class)
                .tenant(new TenantPredicate(tenant.getRealm()))
                .attributes(new AttributePredicate("childAssetType").value(new StringPredicate(ConsoleAsset.DESCRIPTOR.getName())))
        );

        if (consoleParent == null) {
            consoleParent = new GroupAsset(CONSOLE_PARENT_ASSET_NAME, ConsoleAsset.DESCRIPTOR);
            consoleParent.setChildAssetType(ConsoleAsset.DESCRIPTOR.getName());
            consoleParent.setRealm(tenant.getRealm());
            consoleParent = assetStorageService.merge(consoleParent);
        }
        return consoleParent;
    }
}
