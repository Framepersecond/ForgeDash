package dash;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class FeatureFlags {
    public static final String BETA_KEY = "beta.enabled";
    public static final List<String> IDS = List.of("tickets", "notifications", "graphs", "ai", "intelligence", "maintenance", "guardian");
    public static final Set<String> BETA_IDS = Set.of("ai", "intelligence", "maintenance", "guardian");
    private FeatureFlags() {}
    public static boolean enabled(String feature) { String id=n(feature); if(!IDS.contains(id))return true; if(BETA_IDS.contains(id)&&!betaEnabled())return false; return FabricDash.getConfig()!=null&&FabricDash.getConfig().getBoolean("features."+id+".enabled",defaultEnabled(id)); }
    public static boolean configured(String feature) { String id=n(feature); return FabricDash.getConfig()!=null&&FabricDash.getConfig().getBoolean("features."+id+".enabled",defaultEnabled(id)); }
    public static boolean betaEnabled(){return FabricDash.getConfig()!=null&&FabricDash.getConfig().getBoolean(BETA_KEY,false);}
    public static void save(boolean beta,Map<String,Boolean> values){if(FabricDash.getConfig()==null)return;FabricDash.getConfig().set(BETA_KEY,beta);for(String id:IDS)FabricDash.getConfig().set("features."+id+".enabled",values!=null&&Boolean.TRUE.equals(values.get(id)));FabricDash.getConfig().save();}
    public static void applySetupPreset(String requested,boolean beta){if(FabricDash.getConfig()==null)return;String profile=switch(n(requested)){case "minimal","full"->n(requested);default->"normal";};FabricDash.getConfig().set("setup.profile",profile);FabricDash.getConfig().set(BETA_KEY,beta);for(String id:IDS)FabricDash.getConfig().set("features."+id+".enabled",BETA_IDS.contains(id)?beta:!"minimal".equals(profile));FabricDash.getConfig().set("features.advanced.enabled","full".equals(profile));FabricDash.getConfig().save();}
    public static boolean pageVisibleForSetupProfile(String path){String profile=FabricDash.getConfig()==null?"full":n(FabricDash.getConfig().getString("setup.profile","full"));String page=path==null?"":path.split("\\?",2)[0];if("full".equals(profile))return true;Set<String> light=Set.of("/","/console","/players","/files","/plugins","/settings","/updates");if("minimal".equals(profile))return light.contains(page);return !Set.of("/plugin-browser","/permissions","/scheduled-tasks").contains(page);}
    public static String featureForPath(String path){if(path==null)return null;if(path.startsWith("/api/ai"))return "ai";if(path.startsWith("/api/guardian"))return "guardian";if(path.startsWith("/maintenance/staff/"))return "tickets";if(path.startsWith("/maintenance/"))return "maintenance";if(path.startsWith("/notifications/"))return "notifications";if(path.startsWith("/graphs/"))return "graphs";return switch(path){case "/staff","/tickets","/report"->"tickets";case "/notifications"->"notifications";case "/graphs"->"graphs";case "/ai","/doctor"->"ai";case "/intelligence","/status"->"intelligence";case "/maintenance"->"maintenance";case "/guardian"->"guardian";default->null;};}
    public static String featureForAction(String action){String v=n(action);if(v.startsWith("intel_"))return "intelligence";if(v.startsWith("staff_"))return "tickets";if(v.startsWith("guardian_"))return "guardian";if(v.startsWith("ai_"))return "ai";return switch(v){case "save_notification_settings","test_notification"->"notifications";case "spark_profile","doctor_delete_crash","doctor_mark_reviewed"->"maintenance";default->null;};}
    public static boolean defaultEnabled(String feature){return !BETA_IDS.contains(n(feature));}
    private static String n(String value){return value==null?"":value.trim().toLowerCase(Locale.ROOT);}
}
