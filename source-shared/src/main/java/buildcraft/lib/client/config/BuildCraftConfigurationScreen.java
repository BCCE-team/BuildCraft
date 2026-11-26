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
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
//? if >=1.21.11 {
import net.minecraft.client.input.MouseButtonEvent;
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
    private static final int TOP=72, ROW=32, TREE_ROW=20, TREE_TOP=TOP+2, VALUE_TOP=TOP+22;
    private static final int SCROLL_GUTTER=11, SCROLL_WIDTH=6, TREE_INDENT=14;
    private final Screen parent;
    private final ConfigurationRegistry registry=ConfigurationRegistry.INSTANCE;
    private final boolean inWorld, singleplayer;
    private final Object connection;
    private final Map<String,ConfigDefinition> definitions=new LinkedHashMap<>();
    private final Map<String,String> original=new HashMap<>(), edited=new HashMap<>(), effective=new HashMap<>();
    private final Map<String,String> numberDrafts=new HashMap<>();
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
    private long clientRevision, serverRevision, token;
    private int pendingTicks;
    private boolean allowed, pending, saving, rebuild, focusSearch;
    private EditBox search;


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
    private void resetValue(ConfigDefinition d) { numberDrafts.remove(d.id()); edited.put(d.id(),d.defaultValue()); }

    private String description(ConfigDefinition d) { return loc(d.descriptionKey(),d.fallbackDescription()); }
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
    private void changed() { rebuild=true; }
    @Override protected void init() { search=null; draggingScroll=0; layout(); }
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
        return 9+Math.min(Math.max(0,treeRight()-9-36),node.depth()*TREE_INDENT);
    }
    private int tabWidth() { return (width-23)/4; }
    private int tabX(Section s) { return 8+s.ordinal()*(tabWidth()+2); }
    private boolean tabAllowed(Section s) { return (s==Section.CLIENT || s==Section.ADDONS || allowed) && !saving; }
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
            if(d.kind()==Kind.BOOLEAN || d.kind()==Kind.ENUM) {
                button(valueLabel(d),b->{if(d.kind()==Kind.BOOLEAN)edited.put(d.id(),Boolean.toString(!Boolean.parseBoolean(edited.get(d.id()))));else {var choices=d.choices();edited.put(d.id(),choices.get((choices.indexOf(edited.get(d.id()))+1)%choices.size()));} b.setMessage(valueLabel(d));reset.active=!edited.get(d.id()).equals(d.defaultValue());},vx,y+4,vw,20).active=!saving;
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
                }); addRenderableWidget(field);
            }
        }
        int bw=Math.max(48,Math.min(116,(width-40)/4));
        button(tr("reset_all"),b->confirmResetAll(),8,height-25,bw,20).active=!saving&&definitions.values().stream().anyMatch(this::inView);
        button(tr(category==null?"reset_section":"reset_category"),b->confirmReset(category,category==null),16+bw,height-25,bw,20)
            .active=!saving && query.isBlank() && definitions.values().stream().anyMatch(d->inSection(d) && (category==null||d.category().equals(category)));
        button(Component.translatable("gui.cancel"),b->onClose(),width-2*bw-12,height-25,bw,20).active=!saving;
        button(Component.translatable("gui.done"),b->save(),width-bw-8,height-25,bw,20).active=!saving&&!pending;
    }
    private Component valueLabel(ConfigDefinition d) { return d.kind()==Kind.BOOLEAN?Component.translatable(Boolean.parseBoolean(edited.get(d.id()))?"options.on":"options.off"):Component.literal(clip(d.kind()==Kind.ENUM?loc(d.nameKey()+".choice."+edited.get(d.id()).toLowerCase(Locale.ROOT),edited.get(d.id())):edited.get(d.id()),Math.max(1,valueWidth()-8))); }
    private boolean matches(ConfigDefinition d) {
        String q=query.toLowerCase(Locale.ROOT).trim(); if(q.isEmpty())return true;
        return (label(d)+" "+description(d)+" "+loc(d.tooltipKey(),d.fallbackTooltip())+" "+d.id()+" "+String.join("/",d.category())).toLowerCase(Locale.ROOT).contains(q);
    }
    private void buildTree() {
        nodes.clear();
        nodes.addAll(ConfigurationTree.visible(definitions.values().stream().filter(this::inSection).map(ConfigDefinition::category).toList(),expanded));
    }
    private void confirmReset(List<String> path,boolean recursive) {
        Section target=section; boolean targetAddons=addonsView;
        confirmReset(tr(path==null?"reset_section":"reset_category"),d->d.isAddon()==targetAddons && d.editorSection()==target &&
            (path==null || (recursive?ConfigurationTree.under(d.category(),path):d.category().equals(path))));
    }
    private void confirmResetAll() { boolean targetAddons=addonsView; confirmReset(tr("reset_all"),d->d.isAddon()==targetAddons); }
    private void confirmReset(Component title,java.util.function.Predicate<ConfigDefinition> filter) {
        ClientGuiModeNavigation.show(new ConfirmScreen(yes->{if(yes) for(var d:definitions.values()) if(filter.test(d)) resetValue(d);ClientGuiModeNavigation.show(this);},title,tr("reset_confirm")));
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
    private void save() {
        try {
            if(inWorld && minecraft.getConnection()!=connection) throw new IllegalStateException(tr("session_changed").getString());
            var server=changes(Scope.SERVER); changes(Scope.CLIENT);
            if(!server.isEmpty()) {
                if(!allowed)throw new IllegalStateException(tr("denied").getString());
                if(!inWorld) {
                    if(minecraft.level!=null || registry.hasWorld()) throw new IllegalStateException(tr("session_changed").getString());
                    var local=registry.commitLocalDefaults(serverRevision,server);
                    serverRevision=local.revision();
                    for(var d:definitions.values()) if(d.scope()==Scope.SERVER) add(d,local.values().get(d.id()),local.effective().get(d.id()));
                    saveClient(); return;
                }
                // Bound the custom payload even on older transports.
                long bytes=server.entrySet().stream().mapToLong(e->(e.getKey().length()+e.getValue().length())*3L+12).sum();
                if(bytes>24000)throw new IllegalArgumentException(tr("too_large").getString());
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
        int panel=panelAt(mx,my);
        if(panel==0 || delta==0) return false;
        if(!saving && overflows(panel)) scrollTo(panel,ConfigurationScroll.wheel(offset(panel),delta,total(panel),capacity(panel)));
        return true;
    }
    private ConfigurationScroll.Thumb thumb(int panel) {
        return ConfigurationScroll.thumb(trackTop(panel),trackHeight(panel),offset(panel),total(panel),capacity(panel));
    }
    private boolean clickList(double mx,double my,int button) {
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
    private boolean dragList(double my,int button) {
        if(draggingScroll==0 || button!=0) return false;
        int panel=draggingScroll;
        if(!saving) scrollTo(panel,ConfigurationScroll.drag(my-scrollGrab,trackTop(panel),trackHeight(panel),total(panel),capacity(panel)));
        return true;
    }
    private boolean releaseList(int button) {
        if(draggingScroll==0 || button!=0) return false;
        draggingScroll=0; return true;
    }
//? if >=1.21.11 {
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
        return dragList(event.y(),mouseButton(event)) || super.mouseDragged(event,dx,dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        return releaseList(mouseButton(event)) || super.mouseReleased(event);
    }
//?} else {
    @Override public boolean mouseClicked(double mx,double my,int button) {
        return clickList(mx,my,button) || super.mouseClicked(mx,my,button);
    }
    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy) {
        return dragList(my,button) || super.mouseDragged(mx,my,button,dx,dy);
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
    @Override public void onClose(){if(!saving)ClientGuiModeNavigation.show(parent);}
    private String clip(String text,int max){return font.width(text)<=Math.max(1,max)?text:font.plainSubstrByWidth(text,Math.max(1,max-8))+"...";}
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
        for(Section s:Section.values()) {
            int x=tabX(s), w=tabWidth();
            boolean selected=s!=Section.ADDONS && s==section;
            boolean hovered=mx>=x && mx<x+w && my>=TAB_TOP && my<TAB_TOP+TAB_HEIGHT;
            boolean enabled=tabAllowed(s) || selected;
            int outer=selected?0xFF8A8A8A:hovered&&enabled?0xFF666666:0xFF4F4F4F;
            int inner=selected?0xE01C1C1C:hovered&&enabled?0xDD202020:0xD8121212;
            f.draw(x,TAB_TOP,x+w,TAB_TOP+TAB_HEIGHT,outer);
            f.draw(x+1,TAB_TOP+1,x+w-1,TAB_TOP+TAB_HEIGHT-1,inner);
            int color=!enabled?0xFF7F7F7F:selected?0xFFFFFFFF:0xFFE0E0E0;
            Component label=tab(s);
            t.draw(label,x+(w-font.width(label))/2,TAB_TOP+6,color);
        }
        f.draw(6,TOP-2,leftWidth+10,bottom,0xFF555555);
        f.draw(7,TOP-1,leftWidth+9,bottom-1,0xED080808);
        f.draw(rightX-3,TOP-2,width-6,bottom,0xB51B1B1B);
        Component header=addonsView?tr("addons_title"):title;
        t.draw(header,width/2-font.width(header)/2,7,0xFFFFFFFF);
        String path=!query.isBlank()?tr("search_results").getString():category==null?tab(section).getString():String.join(" / ",category.stream().map(this::catName).toList())+(generalCategory?" / "+tr("general").getString():"");
        t.draw(Component.literal(clip(path,Math.max(20,width-rightX-12))),rightX,TOP+4,0xFFFFFFFF);
        if(visible.isEmpty())t.draw(tr(pending?"loading":query.isBlank()&&category==null?(nodes.isEmpty()?"empty_section":"select_category"):"empty"),rightX,TOP+30,0xFFBBBBBB);
        int max=valueX()-rightX-8;
        for(int i=0;i<visible.size();i++){
            var d=visible.get(i);int y=VALUE_TOP+i*ROW;
            f.draw(rightX,y,valueRight(),y+ROW-2,0x702F302E);
            int color=numberDrafts.containsKey(d.id())?0xFFFF7777:edited.get(d.id()).equals(original.get(d.id()))?0xFFFFFFFF:0xFFFFD475;
            var format=numberFormat(d);
            drawHighlightedClipped(f,t,label(d)+(format==null?"":" ["+format.unit()+"]"),rightX+4,y+4,max,color,0x804D4300);
            drawHighlightedClipped(f,t,description(d),rightX+4,y+16,max,0xFFAAAAAA,0x80504000);
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
            f.draw(x,y,treeRight(),y+TREE_ROW-1,selected?0xFF373737:hovered?0xFF282828:0xFF141414);
            if(selected) f.draw(x,y,x+2,y+TREE_ROW-1,0xFFB0B0B0);
            if(node.branch()) drawBranch(f,x+5,y+6,expanded.contains(pathParts),0xFFBBBBBB);
            drawHighlightedClipped(f,t,name,x+17,y+6,treeRight()-x-20,selected?0xFFFFFFFF:0xFFE0E0E0,0x804D4300);
        }
        drawScrollbar(f,1,mx,my); drawScrollbar(f,2,mx,my);
        String status=!error.isEmpty()?error:pending?tr("loading").getString():tr(!inWorld?"local_hint":"restart_hint").getString();
        t.draw(Component.literal(clip(status,width-16)),8,height-40,error.isEmpty()?0xFFBBBBBB:0xFFFF8888);
    }
    private void searchHint(Text t) {
        if(query.isEmpty()&&!search.isFocused())t.draw(tr("search"),12,52,0xFF888888);
    }
    private void tooltip(Fill f,Text t,Runnable nextLayer,int mx,int my) {
        if(mx<rightX||mx>=valueRight()||my<VALUE_TOP||my>=bottom-4||draggingScroll!=0)return;
        int i=(my-VALUE_TOP)/ROW;if(i>=visible.size())return;
        var d=visible.get(i); var lines=new ArrayList<String>();
        if(mx>=resetX())lines.add(tr("reset_value").getString());
        lines.add(label(d));lines.add(tab(d.editorSection()).getString()+" / "+String.join(" / ",d.category().stream().map(this::catName).toList()));lines.add(loc(d.tooltipKey(),d.fallbackTooltip()));lines.add(tr("default").getString()+": "+displayNumber(d,d.defaultValue()));
        if(!effective.get(d.id()).equals(original.get(d.id())))lines.add(tr("effective").getString()+": "+displayNumber(d,effective.get(d.id())));
        if(d.kind()==Kind.INTEGER||d.kind()==Kind.DECIMAL) { var format=numberFormat(d); lines.add(format==null?d.minimum()+" .. "+d.maximum():format.bounds(d.minimum(),d.maximum())); }
        lines.add(tr("policy."+d.applyPolicy().name().toLowerCase(Locale.ROOT)).getString());
        int w=Math.max(80,Math.min(280,width-12));var wrapped=new ArrayList<String>();
        for(String line:lines)for(String part:line.split("\n")){
            String remaining=part;while(!remaining.isEmpty()&&wrapped.size()<Math.max(3,(height-14)/11)){
                String segment=font.plainSubstrByWidth(remaining,w-10);if(segment.isEmpty())break;wrapped.add(segment);remaining=remaining.substring(segment.length()).stripLeading();
            }
        }
        int h=wrapped.size()*11+8,x=Math.max(4,Math.min(mx+9,width-w-4)),y=Math.max(4,Math.min(my+10,height-h-4));
        // Deferred renderers group geometry and glyphs within a stratum. Put the
        // opaque backdrop above all screen/widgets, then the tooltip glyphs above it.
        nextLayer.run();
        f.draw(x-1,y-1,x+w+1,y+h+1,0xFF767676);f.draw(x,y,x+w,y+h,0xFF161616);
        nextLayer.run();
        for(int j=0;j<wrapped.size();j++)t.draw(Component.literal(wrapped.get(j)),x+5,y+4+j*11,j==0?0xFFFFFFFF:0xFFCCCCCC);
    }
//? if >=26.1.2 {
    @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float pt) {}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float pt){
        Text t=(s,x,y,c)->g.text(font,s,x,y,c,true);
        panels(g::fill,t,mx,my);
        super.extractRenderState(g,mx,my,pt);
        searchHint(t);
        tooltip(g::fill,t,g::nextStratum,mx,my);
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
        tooltip(g::fill,t,g::nextStratum,mx,my);
//?} else {
        g.pose().pushPose();
        try {
            g.pose().translate(0,0,400);
            tooltip(g::fill,t,()->{},mx,my);
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
            tooltip(f,t,()->{},mx,my);
        } finally {
            pose.popPose();
        }
    }
//?}
}
