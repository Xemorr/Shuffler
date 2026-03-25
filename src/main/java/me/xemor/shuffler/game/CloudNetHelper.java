package me.xemor.shuffler.game;

import eu.cloudnetservice.driver.inject.InjectionLayer;
import eu.cloudnetservice.modules.bridge.BridgeServiceHelper;
import me.xemor.shuffler.Shuffler;

public class CloudNetHelper {

    public static void setInstanceToInGameAndStartNewInstance() {
        try {
            InjectionLayer.ext().instance(BridgeServiceHelper.class).changeToIngame();
        } catch (Exception e) {
            Shuffler.getInstance().getLogger().warning("Failed to update CloudNet service state: " + e.getMessage());
        }
    }

}
