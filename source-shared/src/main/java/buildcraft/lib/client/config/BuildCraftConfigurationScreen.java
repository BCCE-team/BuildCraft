package buildcraft.lib.client.config;

import buildcraft.api.v2.config.ConfigDefinition;
import buildcraft.api.v2.config.ConfigDefinition.*;
import buildcraft.lib.balance.BalanceConfig;
import buildcraft.core.BCCoreConfig;
import buildcraft.lib.client.gui.ClientGuiModeNavigation;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.internal.config.ConfigurationRegistry;
import buildcraft.lib.net.MessageConfigurationRequest;
import buildcraft.lib.net.MessageConfigurationResponse;
import buildcraft.lib.net.MessageManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
//? if >=1.21.11 {
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
//?}
//? if >=26.1.2 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else if >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
//?}
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.io.IOException;

/** Schema-driven editor. Only the current session chooses which authoritative scope is shown. */
public final class BuildCraftConfigurationScreen extends Screen {
    private static final String PREFIX="gui.buildcraft.configuration.";
    private static final AtomicLong TOKENS=new AtomicLong();
    private static final int TAB_TOP=22, TAB_HEIGHT=20;
    private static final String OIL_PROFILE="buildcraftenergy:worldgen/oil/generationProfile";
    private static final String OIL_CUSTOM="buildcraftenergy:worldgen/oil/customGenerationRate";
    private static final List<String> UPDATE_ACTIONS=List.of("Updates", "Actions");
    private static final int TOP=72, ROW=32, TREE_ROW=20, TREE_TOP=TOP+2, VALUE_TOP=TOP+22;
    private static final int SCROLL_GUTTER=11, SCROLL_WIDTH=6, TREE_INDENT=14;
    private static final int DIRTY_TEXT=0xFFFFD475, DIRTY_BORDER=0xFFB98A43;
    private final Screen parent;
    private final ConfigurationRegistry registry=ConfigurationRegistry.INSTANCE;
    private final boolean inWorld, singleplayer;
    private final Object connection;
    private final Map<String,ConfigDefinition> definitions=new LinkedHashMap<>();
    private final Map<String,String> original=new HashMap<>(), edited=new HashMap<>(), effective=new HashMap<>();
    private final Map<String,String> numberDrafts=new HashMap<>();
    private ConfigurationDirtyPaths dirtyPaths;
    private final Set<List<String>> expanded=new HashSet<>();
    private final List<ConfigDefinition> visible=new ArrayList<>();
    private final List<ConfigurationTree.Node> nodes=new ArrayList<>();
    private Section section=Section.CLIENT;
    private boolean addonsView;
    private List<String> category;
    private boolean generalCategory;
    private String query="", error="";
    private int treeOffset, valueOffset, leftWidth, rightX, bottom, rowCount, treeCount, filteredCount;
    private int draggingScroll; // 0: none, 1: tree, 2: settings
    private double scrollGrab;
    private boolean draggingOil;
    private String updateActionMessage="";
    private long clientRevision, serverRevision, token;
    private int pendingTicks;
    private boolean allowed, pending, saving, rebuild, focusSearch;
    private EditBox search;
    private Confirmation confirmation;
    private boolean confirmFocused;
    private int dialogOffset;

    private record Notice(Component text, int color) {}
    private record Confirmation(Component title, List<Notice> body, Component cancel, Component accept, Runnable action) {}
    private record DialogBounds(int x, int y, int width, int height, ConfigurationTooltipLayout.Window content) {
        int buttonY() { return y+height-28; }
        int buttonWidth() { return (width-30)/2; }
        int cancelX() { return x+10; }
        int acceptX() { return x+width-10-buttonWidth(); }
    }


    public BuildCraftConfigurationScreen(Screen parent) {
        super(Component.translatable(PREFIX+"title"));
        this.parent=parent;
        var mc=Minecraft.getInstance();
        connection=mc.getConnection();
        inWorld=mc.level!=null && connection!=null;
        singleplayer=!inWorld || mc.getSingleplayerServer()!=null;
        BalanceConfig.initialize();
        var snapshot=registry.snapshot(Scope.CLIENT);
        clientRevision=snapshot.revision();
        for(var d:registry.definitions()) if(d.scope()==Scope.CLIENT) add(d,snapshot.values().get(d.id()),snapshot.effective().get(d.id()));
        if(inWorld) request(false,Map.of());
        else {
            // Title-screen values belong to the personal profile, not any remote server.
            var local=registry.snapshot(Scope.SERVER);
            serverRevision=local.revision(); allowed=true;
            for(var d:registry.definitions()) if(d.scope()==Scope.SERVER) add(d,local.values().get(d.id()),local.effective().get(d.id()));
        }
    }
    private void add(ConfigDefinition d,String value,String current) {
        definitions.put(d.id(),d); original.put(d.id(),value); edited.put(d.id(),value); effective.put(d.id(),current); numberDrafts.remove(d.id());
        invalidateDirtyPaths();
    }
    private static Component tr(String key) { return Component.translatable(PREFIX+key); }
    private static String loc(String key,String fallback) { return LocaleUtil.canLocalize(key)?I18n.get(key):fallback; }
    private String label(ConfigDefinition d) { return loc(d.nameKey(),d.fallbackName()); }
    private ConfigurationNumberFormat.Format numberFormat(ConfigDefinition d) { return ConfigurationNumberFormat.forDefinition(d,registry.displayUnits()); }
    private String displayNumber(ConfigDefinition d,String raw) {
        var format=numberFormat(d); return format==null?raw:format.display(raw)+" "+format.unit();
    }
    private boolean nonDefault(ConfigDefinition d) {
        return numberDrafts.containsKey(d.id()) || !edited.get(d.id()).equals(d.defaultValue());
    }
    private void resetValue(ConfigDefinition d) { numberDrafts.remove(d.id()); edited.put(d.id(),d.defaultValue()); invalidateDirtyPaths(); }

    private String description(ConfigDefinition d) { return loc(d.descriptionKey(),d.fallbackDescription()); }
    /** Never calculate with stale values while the user has an invalid numeric draft. */
    private String previewValue(String id) {
        var definition=definitions.get(id);
        if(definition==null || numberDrafts.containsKey(id) || !edited.containsKey(id)) return null;
        try { return definition.validate(edited.get(id)); }
        catch(IllegalArgumentException e) { return null; }
    }
    private List<String> calculatedLines(ConfigDefinition d) {
        return ConfigurationCalculatedValues.preview(d,this::previewValue,registry.displayUnits()).stream()
            .map(line->Component.translatable(PREFIX+"calculated."+line.key(),line.arguments().toArray()).getString()).toList();
    }
    private String catName(String value) { return loc(PREFIX+"category."+value.toLowerCase(Locale.ROOT).replace(' ','_'),value); }
    private Component tab(Section s) { return tr(s==Section.ADDONS?(addonsView?"bcce":"addons"):s==Section.WORLD?(singleplayer?"world":"server"):s.name().toLowerCase(Locale.ROOT)); }
    private boolean inView(ConfigDefinition d) { return d.isAddon()==addonsView; }
    private boolean inSection(ConfigDefinition d) { return inView(d) && d.editorSection()==section; }
    private Button button(Component label,Button.OnPress press,int x,int y,int w,int h) {
//? if >=1.20 {
        return addRenderableWidget(Button.builder(label,press).bounds(x,y,Math.max(1,w),h).build());
//?} else {
        return addRenderableWidget(new Button(x,y,Math.max(1,w),h,label,press));
//?}
    }
    private void invalidateDirtyPaths() { dirtyPaths=null; }
    private ConfigurationDirtyPaths dirtyPaths() {
        if(dirtyPaths==null) dirtyPaths=new ConfigurationDirtyPaths(definitions.values(),original,edited,numberDrafts);
        return dirtyPaths;
    }
    private void changed() { rebuild=true; invalidateDirtyPaths(); }
    @Override protected void init() { search=null; draggingScroll=0; draggingOil=false; layout(); }
    private int treeRight() { return leftWidth+8-SCROLL_GUTTER; }
    private int valueRight() { return width-8-SCROLL_GUTTER; }
    private int resetX() { return valueRight()-19; }
    private int valueWidth() { return Math.min(118,Math.max(48,(valueRight()-rightX)/3)); }
    private int valueX() { return resetX()-13-valueWidth(); }
    private int trackTop(int panel) { return panel==1?TREE_TOP:VALUE_TOP; }
    private int trackHeight(int panel) { return Math.max(0,bottom-4-trackTop(panel)); }
    private int barX(int panel) { return (panel==1?treeRight():valueRight())+3; }
    private int total(int panel) { return panel==1?nodes.size():filteredCount; }
    private int capacity(int panel) { return panel==1?treeCount:rowCount; }
    private int offset(int panel) { return panel==1?treeOffset:valueOffset; }
    private boolean overflows(int panel) { return total(panel)>capacity(panel) && capacity(panel)>0; }
    private int treeRowX(ConfigurationTree.Node node) {
        // Leave room for the branch marker and text even in deeply nested addon categories.
        // Reserve room for a four-digit count even for deeply nested categories.
        return 9+Math.min(Math.max(0,treeRight()-9-54),node.depth()*TREE_INDENT);
    }
    private int tabWidth() { return (width-23)/4; }
    private int tabX(Section s) { return 8+s.ordinal()*(tabWidth()+2); }
    private boolean tabAllowed(Section s) { return (s==Section.CLIENT || s==Section.ADDONS || allowed) && !saving; }
    private boolean updateActions() {
        return !addonsView && section==Section.CLIENT && UPDATE_ACTIONS.equals(category) && query.isBlank();
    }
    private boolean isOilSlider(ConfigDefinition d) { return OIL_CUSTOM.equals(d.id()); }
    private boolean shownSetting(ConfigDefinition d) {
        return !isOilSlider(d) || "CUSTOM".equals(edited.get(OIL_PROFILE));
    }
    private void layout() {
        setFocused(null);
        clearWidgets();
        leftWidth=Math.min(180,Math.max(78,width/4)); rightX=leftWidth+15;
        bottom=height-48;
        rowCount=Math.max(0,trackHeight(2)/ROW); treeCount=Math.max(0,trackHeight(1)/TREE_ROW);
        if(search==null) { search=new EditBox(font,8,47,width-16,18,tr("search")); search.setMaxLength(256); }
        search.setResponder(value->{}); if(!search.getValue().equals(query))search.setValue(query);
        search.setResponder(value->{query=value;valueOffset=0;focusSearch=true;changed();}); addRenderableWidget(search);
        if(focusSearch) { setFocused(search); focusSearch=false; }
        buildTree();
        treeOffset=ConfigurationScroll.clamp(treeOffset,nodes.size(),treeCount);
        List<ConfigDefinition> filtered=definitions.values().stream()
            .filter(this::inView)
            .filter(this::shownSetting)
            .filter(d->!query.isBlank() || (d.editorSection()==section && d.category().equals(category)))
            .filter(this::matches).sorted(Comparator.comparing(d->String.join("/",d.category())+"/"+label(d))).toList();
        filteredCount=filtered.size();
        valueOffset=ConfigurationScroll.clamp(valueOffset,filteredCount,rowCount);
        visible.clear(); visible.addAll(filtered.subList(valueOffset,Math.min(filtered.size(),valueOffset+rowCount)));
        for(int i=0;i<visible.size();i++) {
            var d=visible.get(i); int y=VALUE_TOP+i*ROW;
            int vw=valueWidth(), vx=valueX();
            Button reset=button(Component.literal("\u21ba"),b->{resetValue(d);changed();},resetX(),y+4,19,20);
            reset.active=!saving && nonDefault(d);
            if(isOilSlider(d)) {
                // Exact input complements the mouse slider on small GUI scales.
                EditBox field=new EditBox(font,vx,y+2,vw,16,Component.literal(label(d)));
                field.setMaxLength(24);
                field.setValue(numberDrafts.getOrDefault(d.id(),edited.get(d.id())));
                field.setEditable(!saving);
                field.setResponder(s->{
                    try { edited.put(d.id(),d.validate(s)); numberDrafts.remove(d.id()); }
                    catch(IllegalArgumentException e) { numberDrafts.put(d.id(),s); }
                    reset.active=nonDefault(d);
                    invalidateDirtyPaths();
                });
                addRenderableWidget(field);
                continue;
            }
            if(d.kind()==Kind.BOOLEAN || d.kind()==Kind.ENUM) {
                button(valueLabel(d),b->{if(d.kind()==Kind.BOOLEAN)edited.put(d.id(),Boolean.toString(!Boolean.parseBoolean(edited.get(d.id()))));else {var choices=d.choices();edited.put(d.id(),choices.get((choices.indexOf(edited.get(d.id()))+1)%choices.size()));} b.setMessage(valueLabel(d));reset.active=!edited.get(d.id()).equals(d.defaultValue());invalidateDirtyPaths();if(OIL_PROFILE.equals(d.id()))changed();},vx,y+4,vw,20).active=!saving;
            } else {
                var format=numberFormat(d);
                EditBox field=new EditBox(font,vx,y+4,vw,20,Component.literal(label(d))); field.setMaxLength(4096);
                field.setValue(format==null?edited.get(d.id()):numberDrafts.getOrDefault(d.id(),format.display(edited.get(d.id())))); field.setEditable(!saving);
                field.setResponder(s->{
                    if(format==null) edited.put(d.id(),s);
                    else {
                        try { edited.put(d.id(),d.validate(format.canonical(s,d.kind()))); numberDrafts.remove(d.id()); }
                        catch(IllegalArgumentException|ArithmeticException e) { numberDrafts.put(d.id(),s); }
                    }
                    reset.active=nonDefault(d);
                    invalidateDirtyPaths();
                }); addRenderableWidget(field);
            }
        }
        if(updateActions()) {
            int actionWidth=Math.max(1,(valueRight()-rightX-6)/2);
            button(tr("update_check_now"),b->{
                updateActionMessage="";
                buildcraft.lib.update.client.UpdateNotificationManager.checkNow();
            },rightX,VALUE_TOP+2,actionWidth,20).active=!saving;
            button(tr("update_reset_ignored"),b->showConfirmation(tr("update_reset_ignored"),
                List.of(new Notice(tr("update_reset_confirm"),0xFFDDDDDD),
                        new Notice(tr("dialog.immediate"),0xFFFFD475)),tr("dialog.reset"),()->{
                    updateActionMessage=buildcraft.lib.update.UpdatePreferences.clearIgnoredVersionsChecked()
                        ? "update_reset_done" : "update_reset_failed";
                }),rightX+actionWidth+6,VALUE_TOP+2,actionWidth,20).active=!saving;
        }
        int bw=Math.max(48,Math.min(116,(width-40)/4));
        button(tr("reset_all"),b->confirmResetAll(),8,height-25,bw,20).active=!saving&&definitions.values().stream().anyMatch(this::inView);
        button(tr(category==null?"reset_section":"reset_category"),b->confirmReset(category,category==null),16+bw,height-25,bw,20)
            .active=!saving && query.isBlank() && definitions.values().stream().anyMatch(d->inSection(d) && (category==null||d.category().equals(category)));
        button(Component.translatable("gui.cancel"),b->onClose(),width-2*bw-12,height-25,bw,20).active=!saving;
        button(Component.translatable("gui.done"),b->save(false),width-bw-8,height-25,bw,20).active=!saving&&!pending;
    }
    private String choiceKey(ConfigDefinition d,String value) { return d.nameKey()+".choice."+value.toLowerCase(Locale.ROOT); }
    private String displayValue(ConfigDefinition d,String value) {
        if(d.kind()==Kind.BOOLEAN) return Component.translatable(Boolean.parseBoolean(value)?"options.on":"options.off").getString();
        if(d.kind()==Kind.ENUM) return loc(choiceKey(d,value),value);
        return displayNumber(d,value);
    }
    private Component valueLabel(ConfigDefinition d) { return Component.literal(clip(displayValue(d,edited.get(d.id())),Math.max(1,valueWidth()-8))); }
    private boolean matches(ConfigDefinition d) {
        String q=query.toLowerCase(Locale.ROOT).trim(); if(q.isEmpty())return true;
        return (label(d)+" "+description(d)+" "+loc(d.tooltipKey(),d.fallbackTooltip())+" "+d.id()+" "+String.join("/",d.category())).toLowerCase(Locale.ROOT).contains(q);
    }
    private void buildTree() {
        nodes.clear();
        var paths = new ArrayList<>(definitions.values().stream().filter(this::inSection).map(ConfigDefinition::category).toList());
        if(!addonsView && section==Section.CLIENT && definitions.containsKey("buildcraftcore:display/updates/automaticCheck"))
            paths.add(UPDATE_ACTIONS);
        nodes.addAll(ConfigurationTree.visible(paths,expanded));
    }
    private void confirmReset(List<String> path,boolean recursive) {
        Section target=section; boolean targetAddons=addonsView;
        confirmReset(tr(path==null?"reset_section":"reset_category"),d->d.isAddon()==targetAddons && d.editorSection()==target &&
            (path==null || (recursive?ConfigurationTree.under(d.category(),path):d.category().equals(path))));
    }
    private void confirmResetAll() { boolean targetAddons=addonsView; confirmReset(tr("reset_all"),d->d.isAddon()==targetAddons); }
    private void confirmReset(Component title,java.util.function.Predicate<ConfigDefinition> filter) {
        var targets=definitions.values().stream().filter(filter).map(ConfigDefinition::id).toList();
        showConfirmation(title,List.of(new Notice(tr("reset_confirm"),0xFFDDDDDD)),tr("dialog.reset"),()->{
            for(String id:targets) {
                var d=definitions.get(id);
                if(d!=null) resetValue(d);
            }
            changed();
        });
    }
    private void showConfirmation(Component title,List<Notice> body,Component accept,Runnable action) {
        if(saving || confirmation!=null) return;
        confirmation=new Confirmation(title,List.copyOf(body),Component.translatable("gui.cancel"),accept,action);
        confirmFocused=false; dialogOffset=0;
        draggingScroll=0; draggingOil=false; focusSearch=false; setFocused(null);
    }
    private void closeConfirmation(boolean accept) {
        var current=confirmation;
        if(current==null) return;
        confirmation=null; confirmFocused=false; dialogOffset=0; setFocused(null);
        if(accept) current.action().run();
    }
    private boolean showRestartWarning(Map<String,String> client,Map<String,String> server) {
        var requirements=ConfigurationRestartNotice.requirements(definitions.values(),client,server,effective,inWorld,singleplayer);
        if(requirements.isEmpty()) return false;
        boolean nextWorldOnly=requirements.size()==1 && requirements.containsKey(ConfigurationRestartNotice.Requirement.NEXT_WORLD);
        var lines=new ArrayList<Notice>();
        lines.add(new Notice(tr("dialog.restart.intro"),0xFFDDDDDD));
        for(var entry:requirements.entrySet()) {
            String key="dialog.restart."+entry.getKey().name().toLowerCase(Locale.ROOT);
            lines.add(new Notice(Component.translatable(PREFIX+key,entry.getValue()),0xFFFFD475));
        }
        lines.add(new Notice(tr("dialog.restart.footer"),0xFFAAAAAA));
        showConfirmation(tr(nextWorldOnly?"dialog.next_world.title":"dialog.restart.title"),lines,tr("dialog.save"),()->save(true));
        return true;
    }
    private void request(boolean commit,Map<String,String> changes) {
        if(!inWorld || Minecraft.getInstance().getConnection()!=connection) throw new IllegalStateException(tr("session_changed").getString());
        token=TOKENS.incrementAndGet();pending=true;pendingTicks=0;
        MessageManager.sendToServer(new MessageConfigurationRequest(token,serverRevision,commit,changes));
    }
    private Map<String,String> changes(Scope scope) {
        Map<String,String> result=new LinkedHashMap<>();
        for(var d:definitions.values())if(d.scope()==scope) {
            if(numberDrafts.containsKey(d.id())) throw new IllegalArgumentException(label(d)+": "+tr("invalid_number").getString());
            String next=d.validate(edited.get(d.id())); if(!next.equals(original.get(d.id())))result.put(d.id(),next);
        }
        return result;
    }
    private void save(boolean confirmed) {
        if(saving || pending) return;
        try {
            if(inWorld && minecraft.getConnection()!=connection) throw new IllegalStateException(tr("session_changed").getString());
            var server=changes(Scope.SERVER); var client=changes(Scope.CLIENT);
            if(!server.isEmpty()) {
                if(!allowed)throw new IllegalStateException(tr("denied").getString());
                if(!inWorld && (minecraft.level!=null || registry.hasWorld())) throw new IllegalStateException(tr("session_changed").getString());
                // Bound the custom payload even on older transports.
                if(inWorld) {
                    long bytes=server.entrySet().stream().mapToLong(e->(e.getKey().length()+e.getValue().length())*3L+12).sum();
                    if(bytes>24000)throw new IllegalArgumentException(tr("too_large").getString());
                }
            }
            // Validate every edit before presenting the warning; do not write or send anything until confirmed.
            error="";
            if(!confirmed && showRestartWarning(client,server)) return;
            if(!server.isEmpty()) {
                if(!inWorld) {
                    var local=registry.commitLocalDefaults(serverRevision,server);
                    serverRevision=local.revision();
                    for(var d:definitions.values()) if(d.scope()==Scope.SERVER) add(d,local.values().get(d.id()),local.effective().get(d.id()));
                    saveClient(); return;
                }
                saving=true;request(true,server);changed();return;
            }
            saveClient();
        } catch(IllegalArgumentException|IllegalStateException|IOException e) { error=String.valueOf(e.getMessage());saving=false;changed(); }
    }
    private void saveClient() throws IOException { registry.commit(Scope.CLIENT,clientRevision,changes(Scope.CLIENT)); BCCoreConfig.refreshDisplayUnits(); onClose(); }
    @Override public void tick() {
        super.tick();
        if(pending) {
            var response=MessageConfigurationResponse.latest(token);
            if(response!=null) {
                pending=false;allowed=response.allowed();
                if(response.error().isEmpty())serverRevision=response.revision();
                if(!allowed) {
                    definitions.values().removeIf(d->d.scope()==Scope.SERVER); if(section==Section.WORLD||section==Section.BALANCE){section=Section.CLIENT;category=null;generalCategory=false;}
                    error=tr("denied").getString();saving=false;
                } else if(!response.error().isEmpty()) {error=response.error();saving=false;}
                else {
                    for(var entry:response.entries())add(entry.definition(),entry.value(),entry.effective());
                    if(saving){saving=false;try{saveClient();return;}catch(IOException|IllegalArgumentException e){error=String.valueOf(e.getMessage());}}
                }
                changed();
            } else if(++pendingTicks>200) {pending=false;saving=false;error=tr("timeout").getString();changed();}
        }
        if(rebuild){rebuild=false;layout();}
    }

    private void selectTab(Section next) {
        // Keep the global search query when switching sections.
        if(next==Section.ADDONS) addonsView=!addonsView; else section=next;
        category=null; generalCategory=false; treeOffset=valueOffset=0; expanded.clear();
        setFocused(null); focusSearch=false; changed();
    }
    private boolean clickTab(double mx,double my,int button) {
        if(button!=0 || my<TAB_TOP || my>=TAB_TOP+TAB_HEIGHT) return false;
        for(Section s:Section.values()) {
            int x=tabX(s), w=tabWidth();
            if(mx>=x && mx<x+w) {
                if(s!=section && tabAllowed(s)) selectTab(s);
                return true;
            }
        }
        return false;
    }
    private int searchIndex(String text) {
        String q=query.toLowerCase(Locale.ROOT).trim();
        return q.isEmpty() ? -1 : text.toLowerCase(Locale.ROOT).indexOf(q);
    }
    private void drawHighlightedClipped(Fill f,Text t,String text,int x,int y,int maxWidth,int baseColor,int dimColor) {
        String clipped=clip(text,Math.max(1,maxWidth));
        t.draw(Component.literal(clipped),x,y,baseColor);
        int index=searchIndex(clipped);
        if(index<0) return;
        String q=query.trim();
        int end=Math.min(clipped.length(),index+q.length());
        String before=clipped.substring(0,index), match=clipped.substring(index,end);
        int hx=x+font.width(before), hw=font.width(match);
        f.draw(hx-1,y+1,hx+hw+1,y+font.lineHeight,dimColor);
        t.draw(Component.literal(match),hx,y,0xFFFFFF55);
    }
    /** Keep the two scroll offsets independent; never edit a value while scrolling. */
    private void scrollTo(int panel,int next) {
        next=ConfigurationScroll.clamp(next,total(panel),capacity(panel));
        if(next==offset(panel)) return;
        if(panel==1) treeOffset=next; else valueOffset=next;
        focusSearch=search!=null && search.isFocused();
        // Refresh immediately so a following click cannot target an old row.
        rebuild=false; layout();
    }
    private int panelAt(double mx,double my) {
        if(my>=TREE_TOP && my<bottom-4 && mx>=8 && mx<leftWidth+8) return 1;
        if(my>=VALUE_TOP && my<bottom-4 && mx>=rightX && mx<width-8) return 2;
        return 0;
    }
    private boolean scrollWheel(double mx,double my,double delta) {
        if(confirmation!=null) {
            var content=dialogBounds().content();
            if(delta!=0) dialogOffset=Math.max(0,Math.min(content.maxStart(),content.start()+(delta<0?1:-1)));
            return true;
        }
        int panel=panelAt(mx,my);
        if(panel==0 || delta==0) return false;
        if(!saving && overflows(panel)) scrollTo(panel,ConfigurationScroll.wheel(offset(panel),delta,total(panel),capacity(panel)));
        return true;
    }
    private ConfigurationScroll.Thumb thumb(int panel) {
        return ConfigurationScroll.thumb(trackTop(panel),trackHeight(panel),offset(panel),total(panel),capacity(panel));
    }
    private void setOilSlider(double mouseX) {
        double fraction=Math.max(0,Math.min(1,(mouseX-valueX()-3)/Math.max(1,valueWidth()-6.0)));
        String value=Double.toString(Math.round(fraction*100)/100.0);
        var definition=definitions.get(OIL_CUSTOM);
        if(definition!=null && (!value.equals(edited.get(OIL_CUSTOM)) || numberDrafts.containsKey(OIL_CUSTOM))) {
            edited.put(OIL_CUSTOM,definition.validate(value));
            numberDrafts.remove(OIL_CUSTOM); changed();
        }
    }
    private boolean clickList(double mx,double my,int button) {
        if(confirmation!=null) return clickConfirmation(mx,my,button);
        if(clickTab(mx,my,button)) return true;
        if(button!=0 || saving) return false;
        int panel=panelAt(mx,my);
        if(panel!=0 && overflows(panel) && mx>=barX(panel)-2 && mx<barX(panel)+SCROLL_WIDTH+2) {
            var thumb=thumb(panel);
            setFocused(null); focusSearch=false;
            draggingScroll=panel;
            scrollGrab=thumb.contains(my)?my-thumb.top():thumb.height()/2.0;
            scrollTo(panel,ConfigurationScroll.drag(my-scrollGrab,trackTop(panel),trackHeight(panel),total(panel),capacity(panel)));
            return true;
        }
        if(panel==2 && mx>=valueX() && mx<valueX()+valueWidth()) {
            int row=(int)(my-VALUE_TOP)/ROW;
            if(row>=0 && row<visible.size() && isOilSlider(visible.get(row)) && (my-VALUE_TOP)%ROW>=19 && (my-VALUE_TOP)%ROW<30) {
                setFocused(null); focusSearch=false; draggingOil=true; setOilSlider(mx); return true;
            }
        }
        if(panel==1 && mx<treeRight() && my<TREE_TOP+treeCount*TREE_ROW) {
            int row=(int)(my-TREE_TOP)/TREE_ROW, index=treeOffset+row;
            if(index<nodes.size() && my<TREE_TOP+row*TREE_ROW+TREE_ROW-1) {
                var node=nodes.get(index); List<String> path=node.path();
                if(mx<treeRowX(node)) return false;
                setFocused(null); focusSearch=false;
                if(node.branch()) { if(!expanded.add(path)) expanded.remove(path); }
                else { category=path; generalCategory=node.general(); query=""; valueOffset=0; }
                changed();
                return true;
            }
        }
        return false;
    }
    private boolean dragList(double mx,double my,int button) {
        if(confirmation!=null) return true;
        if(draggingOil && button==0) { if(!saving)setOilSlider(mx); return true; }
        if(draggingScroll==0 || button!=0) return false;
        int panel=draggingScroll;
        if(!saving) scrollTo(panel,ConfigurationScroll.drag(my-scrollGrab,trackTop(panel),trackHeight(panel),total(panel),capacity(panel)));
        return true;
    }
    private boolean releaseList(int button) {
        if(confirmation!=null) return true;
        if(draggingOil && button==0) { draggingOil=false; return true; }
        if(draggingScroll==0 || button!=0) return false;
        draggingScroll=0; return true;
    }
    private boolean confirmationKey(int key) {
        if(confirmation==null) return false;
        if(key==InputConstants.KEY_ESCAPE) closeConfirmation(false);
        // The 26.3 input events use SDL keycodes instead of GLFW keycodes.
//? if >=26.3 {
        else if(key==9) confirmFocused=!confirmFocused;
        else if(key==13 || key==32 || key==0x40000058) closeConfirmation(confirmFocused);
//?} else {
        else if(key==258) confirmFocused=!confirmFocused;
        else if(key==257 || key==32 || key==335) closeConfirmation(confirmFocused);
//?}
        return true;
    }
//? if >=1.21.11 {
    @Override public boolean keyPressed(KeyEvent event) {
        return confirmationKey(event.key()) || super.keyPressed(event);
    }
    @Override public boolean charTyped(CharacterEvent event) {
        return confirmation!=null || super.charTyped(event);
    }
    private static int mouseButton(MouseButtonEvent event) {
        // Both loaders use SDL mouse button numbering as of Minecraft 26.3.
//? if >=26.3 {
        return buildcraft.lib.compat.minecraft.gui.BCGuiInput.legacyButton(event.button());
//?} else {
        return event.button();
//?}
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick) {
        return clickList(event.x(),event.y(),mouseButton(event)) || super.mouseClicked(event,doubleClick);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy) {
        return dragList(event.x(),event.y(),mouseButton(event)) || super.mouseDragged(event,dx,dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        return releaseList(mouseButton(event)) || super.mouseReleased(event);
    }
//?} else {
    @Override public boolean keyPressed(int keyCode,int scanCode,int modifiers) {
        return confirmationKey(keyCode) || super.keyPressed(keyCode,scanCode,modifiers);
    }
    @Override public boolean charTyped(char codePoint,int modifiers) {
        return confirmation!=null || super.charTyped(codePoint,modifiers);
    }
    @Override public boolean mouseClicked(double mx,double my,int button) {
        return clickList(mx,my,button) || super.mouseClicked(mx,my,button);
    }
    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy) {
        return dragList(mx,my,button) || super.mouseDragged(mx,my,button,dx,dy);
    }
    @Override public boolean mouseReleased(double mx,double my,int button) {
        return releaseList(button) || super.mouseReleased(mx,my,button);
    }
//?}
//? if >=1.20.2 {
    @Override public boolean mouseScrolled(double mx,double my,double dx,double dy) {
        return scrollWheel(mx,my,dy) || super.mouseScrolled(mx,my,dx,dy);
    }
//?} else {
    @Override public boolean mouseScrolled(double mx,double my,double delta) {
        return scrollWheel(mx,my,delta) || super.mouseScrolled(mx,my,delta);
    }
//?}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){
        if(confirmation!=null) { closeConfirmation(false); return; }
        if(!saving)ClientGuiModeNavigation.show(parent);
    }
    private String clip(String text,int max){return font.width(text)<=Math.max(1,max)?text:font.plainSubstrByWidth(text,Math.max(1,max-8))+"...";}
    private String countedLabel(String name,int count,int maxWidth) {
        if(count==0) return clip(name,maxWidth);
        String suffix=" ("+count+")";
        int available=maxWidth-font.width(suffix);
        if(available<=0) return suffix.stripLeading();
        String clipped=available<font.width("...")?font.plainSubstrByWidth(name,available):clip(name,available);
        return clipped+suffix;
    }
    @FunctionalInterface private interface Fill{void draw(int x1,int y1,int x2,int y2,int color);}
    @FunctionalInterface private interface Text{void draw(Component text,int x,int y,int color);}
    private void drawScrollbar(Fill f,int panel,int mx,int my) {
        if(!overflows(panel)) return;
        int x=barX(panel),y=trackTop(panel),h=trackHeight(panel);
        f.draw(x-1,y-1,x+SCROLL_WIDTH+1,y+h+1,0xFF454545);
        f.draw(x,y,x+SCROLL_WIDTH,y+h,0xFF080808);
        var thumb=thumb(panel);
        boolean hovered=mx>=x-2 && mx<x+SCROLL_WIDTH+2 && thumb.contains(my);
        int color=draggingScroll==panel?0xFFD0D0D0:hovered?0xFFA5A5A5:0xFF777777;
        f.draw(x,thumb.top(),x+SCROLL_WIDTH,thumb.top()+thumb.height(),color);
        f.draw(x,thumb.top(),x+1,thumb.top()+thumb.height(),0xFFBBBBBB);
    }
    private void drawBranch(Fill f,int x,int y,boolean open,int color) {
        // Pixel-sized chevrons, independent of the selected Minecraft font.
        for(int i=0;i<4;i++) {
            if(open) {
                f.draw(x+i,y+i,x+i+1,y+i+2,color);
                f.draw(x+6-i,y+i,x+7-i,y+i+2,color);
            } else {
                f.draw(x+i,y+i,x+i+2,y+i+1,color);
                f.draw(x+i,y+6-i,x+i+2,y+7-i,color);
            }
        }
    }
    private void panels(Fill f,Text t,int mx,int my) {
        f.draw(0,0,width,height,0xA8000000);
        var dirty=dirtyPaths();
        for(Section s:Section.values()) {
            int x=tabX(s), w=tabWidth();
            boolean selected=s!=Section.ADDONS && s==section;
            boolean hovered=mx>=x && mx<x+w && my>=TAB_TOP && my<TAB_TOP+TAB_HEIGHT;
            boolean enabled=tabAllowed(s) || selected;
            // The fourth tab is a view switch: show edits waiting in the destination view.
            int count=s==Section.ADDONS?dirty.view(!addonsView):dirty.section(addonsView,s);
            int outer=count>0?(selected?DIRTY_TEXT:DIRTY_BORDER):selected?0xFF8A8A8A:hovered&&enabled?0xFF666666:0xFF4F4F4F;
            int inner=count>0?(selected?0xFF42341E:hovered&&enabled?0xFF342A1C:0xFF252017):selected?0xE01C1C1C:hovered&&enabled?0xDD202020:0xD8121212;
            f.draw(x,TAB_TOP,x+w,TAB_TOP+TAB_HEIGHT,outer);
            f.draw(x+1,TAB_TOP+1,x+w-1,TAB_TOP+TAB_HEIGHT-1,inner);
            int color=count>0?DIRTY_TEXT:!enabled?0xFF7F7F7F:selected?0xFFFFFFFF:0xFFE0E0E0;
            Component label=Component.literal(countedLabel(tab(s).getString(),count,w-8));
            t.draw(label,x+(w-font.width(label))/2,TAB_TOP+6,color);
        }
        f.draw(6,TOP-2,leftWidth+10,bottom,0xFF555555);
        f.draw(7,TOP-1,leftWidth+9,bottom-1,0xED080808);
        f.draw(rightX-3,TOP-2,width-6,bottom,0xB51B1B1B);
        Component header=addonsView?tr("addons_title"):title;
        t.draw(header,width/2-font.width(header)/2,7,0xFFFFFFFF);
        String path=!query.isBlank()?tr("search_results").getString():category==null?tab(section).getString():String.join(" / ",category.stream().map(this::catName).toList())+(generalCategory?" / "+tr("general").getString():"");
        int pathCount=query.isBlank()?(category==null?dirty.section(addonsView,section):dirty.category(addonsView,section,category,false)):0;
        t.draw(Component.literal(countedLabel(path,pathCount,Math.max(20,width-rightX-12))),rightX,TOP+4,pathCount>0?DIRTY_TEXT:0xFFFFFFFF);
        if(visible.isEmpty()&&!updateActions())t.draw(tr(pending?"loading":query.isBlank()&&category==null?(nodes.isEmpty()?"empty_section":"select_category"):"empty"),rightX,TOP+30,0xFFBBBBBB);
        if(updateActions() && VALUE_TOP+25<bottom) {
            String key=updateActionMessage.isEmpty()?"update_status."+buildcraft.lib.update.client.UpdateNotificationManager.statusKey():updateActionMessage;
            Component status=Component.translatable(PREFIX+key,buildcraft.lib.update.client.UpdateNotificationManager.latestVersion());
            t.draw(Component.literal(clip(status.getString(),valueRight()-rightX)),rightX+3,VALUE_TOP+28,0xFFCCCCCC);
        }
        int max=valueX()-rightX-8;
        for(int i=0;i<visible.size();i++){
            var d=visible.get(i);int y=VALUE_TOP+i*ROW;
            f.draw(rightX,y,valueRight(),y+ROW-2,0x702F302E);
            int color=numberDrafts.containsKey(d.id())?0xFFFF7777:dirty.contains(d.id())?DIRTY_TEXT:0xFFFFFFFF;
            if(isOilSlider(d)) {
                double value=Double.parseDouble(edited.get(d.id()));
                int x=valueX(), w=valueWidth(), thumb=x+3+(int)Math.round(value*(w-6));
                f.draw(x,y+20,x+w,y+30,0xFF777777);
                f.draw(x+1,y+21,x+w-1,y+29,0xFF111111);
                f.draw(x+3,y+24,x+w-3,y+27,0xFF454545);
                f.draw(x+3,y+24,thumb,y+27,0xFFB2B2B2);
                f.draw(thumb-2,y+22,thumb+2,y+29,0xFFDDDDDD);
            }
            var format=numberFormat(d);
            drawHighlightedClipped(f,t,label(d)+(format==null?"":" ["+format.unit()+"]"),rightX+4,y+4,max,color,0x804D4300);
            var calculated=calculatedLines(d);
            String summary=calculated.isEmpty()?description(d):calculated.get(0);
            int summaryColor=calculated.isEmpty()?0xFFAAAAAA:previewValue(d.id())==null?0xFFFF7777:0xFFA8E38A;
            drawHighlightedClipped(f,t,summary,rightX+4,y+16,max,summaryColor,0x80504000);
        }
        for(int i=0;i<treeCount && treeOffset+i<nodes.size();i++) {
            var node=nodes.get(treeOffset+i); int y=TREE_TOP+i*TREE_ROW, x=treeRowX(node);
            List<String> pathParts=node.path();
            String name=node.general()?tr("general").getString():catName(pathParts.get(pathParts.size()-1));
            boolean selected=!node.branch() && pathParts.equals(category) && query.isBlank() && generalCategory==node.general();
            boolean hovered=!saving && mx>=x && mx<treeRight() && my>=y && my<y+TREE_ROW-1;
            // The whole child row (not only its label) is indented. Guide lines make depth visible.
            if(node.depth()>0) {
                for(int gx=15;gx<x;gx+=TREE_INDENT) f.draw(gx,y,gx+1,y+TREE_ROW,0xFF363636);
                f.draw(Math.max(9,x-8),y+TREE_ROW/2,x,y+TREE_ROW/2+1,0xFF454545);
            }
            // A branch includes descendants; its synthetic General child counts only direct values.
            int count=dirty.category(addonsView,section,pathParts,node.branch());
            f.draw(x,y,treeRight(),y+TREE_ROW-1,count>0?(selected?0xFF42341E:hovered?0xFF342A1C:0xFF252017):selected?0xFF373737:hovered?0xFF282828:0xFF141414);
            if(selected) f.draw(x,y,x+2,y+TREE_ROW-1,count>0?DIRTY_TEXT:0xFFB0B0B0);
            else if(count>0) f.draw(x,y,x+1,y+TREE_ROW-1,DIRTY_BORDER);
            if(node.branch()) drawBranch(f,x+5,y+6,expanded.contains(pathParts),count>0?DIRTY_TEXT:0xFFBBBBBB);
            String suffix=count>0?"("+count+")":"";
            int suffixWidth=font.width(suffix);
            int nameWidth=treeRight()-x-20-(count>0?suffixWidth+4:0);
            if(nameWidth>=font.width("...")) drawHighlightedClipped(f,t,name,x+17,y+6,nameWidth,count>0?DIRTY_TEXT:selected?0xFFFFFFFF:0xFFE0E0E0,0x804D4300);
            else if(nameWidth>0) t.draw(Component.literal(font.plainSubstrByWidth(name,nameWidth)),x+17,y+6,count>0?DIRTY_TEXT:0xFFE0E0E0);
            if(count>0) t.draw(Component.literal(suffix),treeRight()-3-suffixWidth,y+6,DIRTY_TEXT);
        }
        drawScrollbar(f,1,mx,my); drawScrollbar(f,2,mx,my);
        String status=!error.isEmpty()?error:pending?tr("loading").getString():tr(!inWorld?"dialog.personal_hint":"dialog.editor_hint").getString();
        t.draw(Component.literal(clip(status,width-16)),8,height-40,error.isEmpty()?0xFFBBBBBB:0xFFFF8888);
    }
    private void searchHint(Text t) {
        if(query.isEmpty()&&!search.isFocused())t.draw(tr("search"),12,52,0xFF888888);
    }
    private DialogBounds dialogBounds() {
        int w=Math.min(340,Math.max(1,width-16));
        var content=new ConfigurationTooltipLayout(Math.max(1,w-24),font::width);
        content.paragraph(confirmation.title().getString(),0xFFFFFFFF,0,0);
        for(Notice notice:confirmation.body()) content.paragraph(notice.text().getString(),notice.color(),0,6);
        var lines=content.lines();
        int contentHeight=Math.min(ConfigurationTooltipLayout.height(lines),Math.max(0,height-60));
        var window=ConfigurationTooltipLayout.window(lines,dialogOffset,contentHeight);
        int h=contentHeight+48;
        return new DialogBounds((width-w)/2,(height-h)/2,w,h,window);
    }
    private boolean clickConfirmation(double mx,double my,int button) {
        if(button==0) {
            var box=dialogBounds();
            if(my>=box.buttonY() && my<box.buttonY()+20) {
                if(mx>=box.cancelX() && mx<box.cancelX()+box.buttonWidth()) closeConfirmation(false);
                else if(mx>=box.acceptX() && mx<box.acceptX()+box.buttonWidth()) closeConfirmation(true);
            }
        }
        return true; // Even clicks outside the dialog cannot reach the editor.
    }
    private void dialogButton(Fill f,int x,int y,int w,boolean focused,int mx,int my) {
        boolean hover=mx>=x && mx<x+w && my>=y && my<y+20;
        f.draw(x,y,x+w,y+20,focused||hover?0xFFAAAAAA:0xFF555555);
        f.draw(x+1,y+1,x+w-1,y+19,hover?0xFF373737:focused?0xFF282828:0xFF141414);
    }
    private void dialogButtonLabel(Text t,Component label,int x,int y,int w) {
        String text=clip(label.getString(),w-8);
        t.draw(Component.literal(text),x+(w-font.width(text))/2,y+6,0xFFE0E0E0);
    }
    private void drawConfirmation(Fill f,Text t,Runnable nextLayer,int mx,int my) {
        var box=dialogBounds(); int x=box.x(), y=box.y(), w=box.width(), h=box.height();
        // On deferred renderers, never put dimming, opaque panel and foreground in the same stratum.
        nextLayer.run();
        f.draw(0,0,width,height,0x99000000);
        nextLayer.run();
        f.draw(x-1,y-1,x+w+1,y+h+1,0xFF767676);
        f.draw(x,y,x+w,y+h,0xFF161616);
        f.draw(x+10,box.buttonY()-7,x+w-10,box.buttonY()-6,0xFF454545);
        var content=box.content();
        if(content.maxStart()>0) {
            int track=y+10, span=Math.max(1,box.buttonY()-8-track), thumb=Math.min(12,span);
            int scrollY=track+(span-thumb)*content.start()/content.maxStart();
            f.draw(x+w-7,track,x+w-4,track+span,0xFF353535);
            f.draw(x+w-7,scrollY,x+w-4,scrollY+thumb,0xFFAAAAAA);
        }
        dialogButton(f,box.cancelX(),box.buttonY(),box.buttonWidth(),!confirmFocused,mx,my);
        dialogButton(f,box.acceptX(),box.buttonY(),box.buttonWidth(),confirmFocused,mx,my);
        nextLayer.run();
        int lineY=y+10;
        for(var line:content.lines()) {
            lineY+=line.gap();
            t.draw(Component.literal(line.text()),x+12+line.indent(),lineY,line.color());
            lineY+=ConfigurationTooltipLayout.LINE_HEIGHT;
        }
        dialogButtonLabel(t,confirmation.cancel(),box.cancelX(),box.buttonY(),box.buttonWidth());
        dialogButtonLabel(t,confirmation.accept(),box.acceptX(),box.buttonY(),box.buttonWidth());
    }
    private void tooltip(Fill f,Text t,Runnable nextLayer,int mx,int my) {
        if(mx<rightX||mx>=valueRight()||my<VALUE_TOP||my>=bottom-4||draggingScroll!=0||draggingOil)return;
        int i=(my-VALUE_TOP)/ROW;if(i>=visible.size() || (my-VALUE_TOP)%ROW>=ROW-2)return;
        var d=visible.get(i);
        int w=Math.min(320,width-12);
        if(w<32 || height<24) return;
        int contentWidth=w-12;
        var header=new ConfigurationTooltipLayout(contentWidth,font::width);
        var body=new ConfigurationTooltipLayout(contentWidth,font::width);
        var footer=new ConfigurationTooltipLayout(contentWidth,font::width);
        header.paragraph(label(d),0xFFFFFFFF,0,0);
        header.paragraph(tab(d.editorSection()).getString()+" / "+String.join(" / ",d.category().stream().map(this::catName).toList()),0xFF999999,0,0);
        if(mx>=resetX()) header.paragraph(tr("reset_value").getString(),0xFFFFD475,0,4);
        var calculated=calculatedLines(d);
        if(!calculated.isEmpty()) {
            body.paragraph(tr("calculated.heading").getString(),0xFFAAAAAA,0,6);
            int color=previewValue(d.id())==null?0xFFFF7777:0xFFA8E38A;
            for(String line:calculated) body.paragraph(line,color,0,2);
        }
        body.paragraph(loc(d.tooltipKey(),d.fallbackTooltip()),0xFFDDDDDD,0,6);
        if(d.kind()==Kind.ENUM) {
            boolean explained=d.choices().stream().anyMatch(v->LocaleUtil.canLocalize(choiceKey(d,v)+".description"));
            if(explained || d.choices().size()<=8) {
                body.paragraph(tr("choices").getString(),0xFFAAAAAA,0,6);
                if(explained) {
                    for(String choice:d.choices()) {
                        int color=choice.equals(edited.get(d.id()))?0xFFA8E38A:0xFFFFD475;
                        String detail=loc(choiceKey(d,choice)+".description","");
                        if(detail.isBlank()) body.paragraph(displayValue(d,choice),color,0,2);
                        else body.labelled(displayValue(d,choice),color,detail,0xFFDDDDDD,2);
                    }
                } else {
                    body.paragraph(String.join(", ",d.choices().stream().map(v->displayValue(d,v)).toList()),0xFFDDDDDD,0,0);
                }
            }
        }
        footer.paragraph(tr("details").getString(),0xFFAAAAAA,0,6);
        footer.labelled(tr("default").getString(),0xFFAAAAAA,displayValue(d,d.defaultValue()),0xFFFFFFFF,0);
        if(!effective.get(d.id()).equals(original.get(d.id()))) footer.labelled(tr("effective").getString(),0xFFAAAAAA,displayValue(d,effective.get(d.id())),0xFFFFFFFF,0);
        if(d.kind()==Kind.INTEGER||d.kind()==Kind.DECIMAL) {
            var format=numberFormat(d);
            footer.labelled(tr("range").getString(),0xFFAAAAAA,format==null?d.minimum()+" .. "+d.maximum():format.bounds(d.minimum(),d.maximum()),0xFFFFFFFF,0);
        }
        footer.paragraph(tr("policy."+d.applyPolicy().name().toLowerCase(Locale.ROOT)).getString(),d.applyPolicy()==ApplyPolicy.LIVE?0xFFA8E38A:0xFFFFD475,0,4);
        var wrapped=ConfigurationTooltipLayout.fit(header.lines(),body.lines(),footer.lines(),height-22);
        if(wrapped.isEmpty())return;
        int h=ConfigurationTooltipLayout.height(wrapped)+10;
        int x=mx+9+w+4<=width?mx+9:Math.max(4,mx-w-9);
        x=Math.max(4,Math.min(x,width-w-4));
        int y=Math.max(4,Math.min(my+10,height-h-4));
        // Keep the opaque backdrop and its text above the entire screen on deferred renderers.
        nextLayer.run();
        f.draw(x-1,y-1,x+w+1,y+h+1,0xFF767676);f.draw(x,y,x+w,y+h,0xFF161616);
        nextLayer.run();
        int lineY=y+5;
        for(int j=0;j<wrapped.size();j++) {
            var line=wrapped.get(j);
            lineY+=line.gap();
            int lineX=x+6+line.indent();
            if(!line.prefix().isEmpty()) {
                t.draw(Component.literal(line.prefix()),lineX,lineY,line.prefixColor());
                lineX+=font.width(line.prefix());
            }
            t.draw(Component.literal(line.text()),lineX,lineY,line.color());
            lineY+=ConfigurationTooltipLayout.LINE_HEIGHT;
        }
    }
//? if >=26.1.2 {
    @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float pt) {}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float pt){
        Text t=(s,x,y,c)->g.text(font,s,x,y,c,true);
        panels(g::fill,t,mx,my);
        super.extractRenderState(g,mx,my,pt);
        searchHint(t);
        if(confirmation!=null) drawConfirmation(g::fill,t,g::nextStratum,mx,my);
        else tooltip(g::fill,t,g::nextStratum,mx,my);
    }
//?} else if >=1.20 {
//? if >=1.21 {
    @Override public void renderBackground(GuiGraphics g,int mx,int my,float pt) {}
//?} else {
    @Override public void renderBackground(GuiGraphics g) {}
//?}
    @Override public void render(GuiGraphics g,int mx,int my,float pt){
        Text t=(s,x,y,c)->g.drawString(font,s,x,y,c);
        panels(g::fill,t,mx,my);
        super.render(g,mx,my,pt);
        searchHint(t);
//? if >=1.21.11 {
        if(confirmation!=null) drawConfirmation(g::fill,t,g::nextStratum,mx,my);
        else tooltip(g::fill,t,g::nextStratum,mx,my);
//?} else {
        g.pose().pushPose();
        try {
            g.pose().translate(0,0,400);
            if(confirmation!=null) drawConfirmation(g::fill,t,()->{},mx,my);
            else tooltip(g::fill,t,()->{},mx,my);
        } finally {
            g.pose().popPose();
        }
//?}
    }
//?} else {
    @Override public void renderBackground(PoseStack pose) {}
    @Override public void render(PoseStack pose,int mx,int my,float pt){
        Fill f=(a,b,c,d,e)->GuiComponent.fill(pose,a,b,c,d,e);Text t=(s,x,y,c)->GuiComponent.drawString(pose,font,s,x,y,c);
        panels(f,t,mx,my);
        super.render(pose,mx,my,pt);
        searchHint(t);
        pose.pushPose();
        try {
            pose.translate(0,0,400);
            if(confirmation!=null) drawConfirmation(f,t,()->{},mx,my);
            else tooltip(f,t,()->{},mx,my);
        } finally {
            pose.popPose();
        }
    }
//?}
}
