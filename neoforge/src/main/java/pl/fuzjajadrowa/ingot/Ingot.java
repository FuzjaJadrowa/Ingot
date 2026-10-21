package pl.fuzjajadrowa.ingot;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(Ingot.MOD_ID)
public final class Ingot {
    public static final String MOD_ID = "ingot";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Ingot() {
        LOGGER.info("Ingot loaded!");
    }
}