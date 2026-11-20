package dev.saikomc;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Registers SaikoMC as the Vault economy so other plugins (Clan+, etc.) use SaikoMC money.
 * Built with reflection, so SaikoMC still works without Vault installed.
 */
final class VaultHook {
    private VaultHook() {
    }

    /** Returns true if registered, false if Vault is not installed. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean register(SaikoMC p) throws Exception {
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null) return false;
        ClassLoader cl = vault.getClass().getClassLoader();
        Class<?> econ = Class.forName("net.milkbowl.vault.economy.Economy", true, cl);
        Class<?> resp = Class.forName("net.milkbowl.vault.economy.EconomyResponse", true, cl);
        Class<?> type = Class.forName("net.milkbowl.vault.economy.EconomyResponse$ResponseType", true, cl);
        Constructor<?> ctor = resp.getConstructor(double.class, double.class, type, String.class);
        Object proxy = Proxy.newProxyInstance(cl, new Class<?>[]{econ}, new Handler(p, ctor, type));
        Bukkit.getServicesManager().register((Class) econ, proxy, p, ServicePriority.Highest);
        return true;
    }

    private static final class Handler implements InvocationHandler {
        private final SaikoMC p;
        private final Constructor<?> ctor;
        private final Class<?> type;

        Handler(SaikoMC p, Constructor<?> ctor, Class<?> type) {
            this.p = p;
            this.ctor = ctor;
            this.type = type;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            String n = m.getName();
            if (m.getDeclaringClass() == Object.class) {
                switch (n) {
                    case "toString": return "SaikoMC-Economy";
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    default: return null;
                }
            }
            switch (n) {
                case "isEnabled": return true;
                case "getName": return "SaikoMC";
                case "hasBankSupport": return false;
                case "fractionalDigits": return 0;
                case "format": return ChatColor.stripColor(p.fmt(Math.round(num(args[0]))));
                case "currencyNameSingular": return p.getConfig().getString("currency.name-singular", "dollar");
                case "currencyNamePlural": return p.getConfig().getString("currency.name-plural", "dollars");
                case "hasAccount":
                case "createPlayerAccount":
                    return true;
                case "getBalance": {
                    Economy eco = p.eco();
                    return eco == null ? 0.0 : (double) eco.get(id(args[0]));
                }
                case "has": {
                    Economy eco = p.eco();
                    return eco != null && eco.get(id(args[0])) >= num(args[args.length - 1]);
                }
                case "withdrawPlayer": return withdraw(args);
                case "depositPlayer": return deposit(args);
                case "getBanks": return Collections.emptyList();
                default: break;
            }
            // bank methods and anything unknown
            Class<?> rt = m.getReturnType();
            if (rt == boolean.class) return false;
            if (rt == double.class) return 0.0;
            if (rt == int.class) return 0;
            if (rt == void.class) return null;
            if (rt == String.class) return "";
            if (List.class.isAssignableFrom(rt)) return Collections.emptyList();
            if (rt.getName().endsWith("EconomyResponse")) {
                return resp(0, 0, "NOT_IMPLEMENTED", "SaikoMC has no bank support");
            }
            return null;
        }

        private Object withdraw(Object[] args) throws Exception {
            Economy eco = p.eco();
            if (eco == null) return resp(0, 0, "FAILURE", "Economy not ready");
            UUID id = id(args[0]);
            double amount = num(args[args.length - 1]);
            long bal = eco.get(id);
            if (amount < 0) return resp(0, bal, "FAILURE", "Cannot withdraw negative funds");
            long cost = (long) Math.ceil(amount);
            if (!eco.take(id, cost)) return resp(0, bal, "FAILURE", "Insufficient funds");
            return resp(cost, eco.get(id), "SUCCESS", "");
        }

        private Object deposit(Object[] args) throws Exception {
            Economy eco = p.eco();
            if (eco == null) return resp(0, 0, "FAILURE", "Economy not ready");
            UUID id = id(args[0]);
            double amount = num(args[args.length - 1]);
            long bal = eco.get(id);
            if (amount < 0) return resp(0, bal, "FAILURE", "Cannot deposit negative funds");
            long add = Math.round(amount);
            eco.add(id, add);
            return resp(add, eco.get(id), "SUCCESS", "");
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Object resp(double amount, double bal, String t, String err) throws Exception {
            return ctor.newInstance(amount, bal, Enum.valueOf((Class) type, t), err);
        }

        @SuppressWarnings("deprecation")
        private UUID id(Object o) {
            if (o instanceof OfflinePlayer) return ((OfflinePlayer) o).getUniqueId();
            if (o instanceof String) {
                Economy eco = p.eco();
                UUID u = eco == null ? null : eco.find((String) o);
                if (u != null) return u;
                return Bukkit.getOfflinePlayer((String) o).getUniqueId();
            }
            return new UUID(0L, 0L);
        }

        private double num(Object o) {
            return ((Number) o).doubleValue();
        }
    }
}
