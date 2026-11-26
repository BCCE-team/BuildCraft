package buildcraft.lib.client.config;

import buildcraft.api.v2.config.ConfigDefinition;
import buildcraft.api.v2.config.DisplayUnits;
import java.math.BigDecimal;

/** Reversible display scaling; raw storage and network values never change units. */
final class ConfigurationNumberFormat {
    record Format(BigDecimal factor, String unit) {
        String display(String raw) { return new BigDecimal(raw).multiply(factor).stripTrailingZeros().toPlainString(); }
        String canonical(String text, ConfigDefinition.Kind kind) {
            if (text.length()>128) throw new IllegalArgumentException("Number is too long");
            BigDecimal input = new BigDecimal(text.trim());
            if (Math.abs((long)input.scale())>128) throw new IllegalArgumentException("Exponent is too large");
            BigDecimal value = input.divide(factor);
            return kind == ConfigDefinition.Kind.INTEGER ? value.toBigIntegerExact().toString() : value.stripTrailingZeros().toPlainString();
        }
        String bounds(double min, double max) {
            return display(BigDecimal.valueOf(min).toPlainString())+" .. "+display(BigDecimal.valueOf(max).toPlainString())+" "+unit;
        }
    }
    private static Format of(BigDecimal factor,String unit) { return new Format(factor,unit); }
    static Format forDefinition(ConfigDefinition d, DisplayUnits units) {
        if (d.kind()!=ConfigDefinition.Kind.INTEGER && d.kind()!=ConfigDefinition.Kind.DECIMAL) return null;
        BigDecimal rate=BigDecimal.valueOf(units.rate().ticks());
        BigDecimal fluid=units.fluid().convert(BigDecimal.ONE);
        String gap=units.rate().suffix(), volume=units.fluid().symbol();
        String id=d.id();
        if(id.equals("buildcrafttransport:general/pipes/mjPerItem")) return of(new BigDecimal("0.000001"),"MJ/item");
        if(id.equals("buildcrafttransport:general/pipes/mjPerMillibucket")) return of(new BigDecimal("0.000001").divide(fluid),"MJ/"+volume);
        if(id.equals("buildcrafttransport:general/pipes/baseFluidRate")) return of(rate.multiply(fluid),volume+gap);
        if(id.equals("buildcrafttransport:general/pipes/baseFeRate")) return of(rate,"FE"+gap);
        if(id.equals("buildcraftcore:power/mjPerFe")) return of(BigDecimal.ONE,"MJ/FE");
        if(id.equals("buildcraft:balance/machine/buildcraftfactory/pump/tank_capacity_mb")) return of(fluid,volume);
        if(id.equals("buildcraft:balance/machine/buildcraftfactory/pump/energy_per_bucket_mj")) return of(BigDecimal.ONE.divide(fluid.movePointRight(3)),"MJ/"+volume);
        if(id.equals("buildcraft:balance/machine/buildcraftbuilders/quarry/max_tasks_per_tick")) return of(rate,"tasks"+gap);
        if(id.equals("buildcraft:balance/machine/buildcraftbuilders/quarry/max_frame_speed") || id.equals("buildcraft:balance/machine/buildcraftbuilders/quarry/max_mine_rate")) return of(rate.divide(BigDecimal.valueOf(20)),"blocks"+gap);
        return null;
    }
}
