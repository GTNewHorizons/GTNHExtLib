package com.gtnewhorizons.gtnhextlib.core.rfb;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.falsepattern.deploader.DeploaderStub;
import com.gtnewhorizons.retrofuturabootstrap.api.PluginContext;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbPlugin;

public class GTNHExtLibRfbPlugin implements RfbPlugin {

    static {
        DeploaderStub.bootstrap(true);
        DeploaderStub.runDepLoader();
    }

    @Override
    public void onConstruction(@NotNull PluginContext ctx) {}

    @Override
    public @NotNull RfbClassTransformer @Nullable [] makeTransformers() {
        return null;
    }
}
