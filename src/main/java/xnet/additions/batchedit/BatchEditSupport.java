package xnet.additions.batchedit;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Connector types whose createGui/update contracts are verified for the
 * headless, changes-only batch path used by this addon.
 */
public final class BatchEditSupport {

    private static final Set<String> VERIFIED_TYPE_IDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "xnet.item",
                    "xnet.fluid",
                    "xnet.energy",
                    "xnet.logic",
                    "mekanism.gas",
                    "botania.mana",
                    "ic2.eu",
                    "tc.essentia"
            ))
    );

    private BatchEditSupport() {
    }

    public static boolean isSupported(String typeId) {
        return typeId != null && VERIFIED_TYPE_IDS.contains(typeId);
    }
}
