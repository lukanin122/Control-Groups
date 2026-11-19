package controlgroups;

import arc.Core;
import arc.files.Fi;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.FrameBuffer;
import arc.graphics.gl.Shader;
import arc.math.Mat;
import arc.struct.IntSeq;
import arc.struct.Seq;
import arc.util.Buffers;
import arc.util.ScreenUtils;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import java.lang.invoke.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.util.*;
import java.util.function.LongConsumer;

/** Stage A measurement client. All instrumentation belongs to the disposable snapshot. */
public final class StageAResearch implements LongConsumer {
    private final Fi out;
    private final boolean screening="screening".equals(System.getProperty("stageA.mode"));
    private final boolean clippingOnly="clipping".equals(System.getProperty("stageA.mode"))||"timing".equals(System.getProperty("stageA.mode"));
    private final boolean timingOnly="timing".equals(System.getProperty("stageA.mode"));
    private final GroupsHud hud;
    private final UnitProjection[] renderers;
    private final String[] names={"before","production","heat-hoist","color-hoist","raw-alpha-skip"};
    private final Batch nativeTiming=Core.batch;
    private final StageABatch.Counting batch=new StageABatch.Counting();
    private final StageABatch.Null noGL=new StageABatch.Null();
    private final Seq<Unit> units=new Seq<>();
    private final FrameBuffer target=new FrameBuffer(1280,512),qualityTarget=new FrameBuffer(512,512);
    private final Mat identity=new Mat();
    private final com.sun.management.ThreadMXBean alloc=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private final long thread=Thread.currentThread().getId();
    private final StringBuilder times=new StringBuilder("scope,type,state,N,advanced,P,alpha,variant,block,p50-us,p95-us,p99-us,min-us,max-us,completed-us,B-per-packet\n");
    private final StringBuilder counts=new StringBuilder("scope,type,state,N,advanced,P,variant,quads,zero-alpha,flush-requests,nonempty,blend-changes,GL-draws,GL-textures,GL-scissor,GL-uploads\n");
    private final StringBuilder pixels=new StringBuilder("scope,type,state,N,P,alpha,variant,changed,max-rgb,max-alpha\n");
    private final StringBuilder clipping=new StringBuilder("width,config,parent,shader,draw-count,ordered-draw-state-equal\n");
    private String lastClipTrace;
    private Field hudProjection;
    private Object oldHudProjection;
    private int stage,warm;
    private volatile long emptySink;
    private int frameBlock,frameSample,scene;
    private final int[] modes={0,1,2,3,3,2,1,0};
    private final long[] frameTimes=new long[120],hudTimes=new long[120];
    private final StringBuilder frames=new StringBuilder("units,block,mode,active-groups,unique-representatives,p50-frame-us,p95-frame-us,p99-frame-us,min-frame-us,max-frame-us,p50-hud-us,p95-hud-us\n");

    public StageAResearch(Fi out){
        this.out=out;hud=(GroupsHud)Core.scene.find("control-groups-hud");
        renderers=new UnitProjection[]{new BeforeProjection(hud.profiles),new UnitProjection(hud.profiles),new HeatProjection(hud.profiles),new ColorsProjection(hud.profiles),new RawSkipProjection(hud.profiles)};
    }
    @Override public void accept(long elapsed){
        try{
            switch(stage){
                case 0->{setup();stage=1;}
                case 1->{if(++warm>=90){if(!clippingOnly){micro();quality();customBlend();callerContexts();}hudMatrix();save();stage=timingOnly?6:2;warm=0;if(!timingOnly)Core.graphics.setWindowSize(640,480);}}
                case 2->{if(++warm>=3){hudMatrix();stage=3;warm=0;Core.graphics.setWindowSize(320,240);}}
                case 3->{if(++warm>=3){hudMatrix();stage=4;warm=0;Core.graphics.setWindowSize(1280,900);}}
                case 4->{if(++warm>=3){if(screening||clippingOnly){stage=6;}else{setupFrames(500);stage=5;warm=-200;}}}
                case 5->frame(elapsed);
                default->{save();dispose();Core.app.exit();}
            }
        }catch(Throwable error){save();out.child("FAILED.txt").writeString(arc.util.Strings.getStackTrace(error));error.printStackTrace();System.exit(2);}
    }
    private void setup() throws Exception {
        Core.settings.put("fpscap",0);Core.settings.put("effects",false);Core.settings.put("commandmodehold",false);
        Vars.logic.reset();Vars.state.rules=new mindustry.game.Rules();
        Vars.world.loadGenerator(96,96,tiles->{tiles.fill();tiles.eachTile(t->t.setFloor(Blocks.stone.asFloor()));tiles.getn(20,20).setBlock(Blocks.coreShard,Team.sharded,0);});
        Vars.state.rules.waves=false;Vars.state.rules.unitCap=20000;Vars.state.rules.infiniteResources=true;
        Vars.logic.play();Vars.player.team(Team.sharded);Vars.player.unit(UnitTypes.alpha.spawn(Team.sharded,160,160));
        Vars.state.set(mindustry.core.GameState.State.paused);Vars.control.input.commandMode=true;
        for(UnitType type:new UnitType[]{UnitTypes.conquer,UnitTypes.oct,UnitTypes.disrupt,UnitTypes.reign,UnitTypes.eclipse})for(int i=0;i<11;i++)units.add(type.spawn(Team.sharded,450,450));
        Vars.state.teams.updateTeamStats();hud.settings.unassigned=true;hud.settings.collapse=false;hud.settings.collapseCommand=hud.settings.collapseBuild=false;
        hud.settings.editorSchematic=false;Core.scene.act(1f/60);
        hudProjection=GroupsHud.class.getDeclaredField("projection");hudProjection.setAccessible(true);oldHudProjection=hudProjection.get(hud);
        out.child("environment.txt").writeString("Baseline=415a6552eb0cc3d9cdff4a59d164b98f98049a47\nJava="+System.getProperty("java.version")+"\nGL="+Core.gl.glGetString(Gl.version)+"\nRenderer="+Core.gl.glGetString(Gl.renderer)+"\nCore.gl30="+(Core.gl30!=null)+"\nScreening="+screening+"\nNativeTimingBatch="+nativeTiming.getClass().getName()+"\n");
    }
    private void pose(Unit u,int state){
        u.rotation=state==7?45:90;u.elevation=u.type.flying?1:0;u.vel.setZero();
        u.health=u.maxHealth;u.hitTime=state==3?.75f:0;u.healTime=state==4?.8f:0;u.drownTime=state==5?.65f:0;u.lastDrownFloor=Blocks.deepwater.asFloor();
        u.shield=state==6?100:0;u.shieldAlpha=state==6?1:0;
        float p=state==0?0:state==1?.5f:1;
        for(var m:u.mounts){m.rotation=state==7?90:0;m.warmup=m.heat=m.charge=m.recoil=m.smoothReload=p;m.reload=p*m.weapon.reload;if(m.recoils!=null)Arrays.fill(m.recoils,p);}
    }
    private void configure(Batch b){Core.batch=b;Draw.reset();Draw.scl(1);Draw.proj(0,0,1280,512);Draw.trans(identity);Draw.blend();}
    private void packet(UnitProjection renderer,Unit unit,int n,boolean advanced,float p,float alpha,boolean clear){
        if(clear)Gl.clear(Gl.colorBufferBit);
        for(int g=0;g<n;g++)renderer.draw(unit,g,(g+.5f)*1280/n,256,p,180,unit.type.hitSize,0,advanced,alpha);
        Draw.flush();
    }
    private void timed(String scope,Unit u,int state,int n,boolean advanced,float p,float alpha,int mode,int block,Batch b){
        UnitProjection r=renderers[mode];configure(b);boolean gl=b!=noGL;
        for(int i=0;i<200;i++)packet(r,u,n,advanced,p,alpha,gl);
        if(gl)Core.gl.glFinish();long[] samples=new long[256];long bytes=alloc.getThreadAllocatedBytes(thread),total=System.nanoTime();
        for(int i=0;i<samples.length;i++){long start=System.nanoTime();packet(r,u,n,advanced,p,alpha,gl);samples[i]=System.nanoTime()-start;}
        if(gl)Core.gl.glFinish();double complete=(System.nanoTime()-total)/(1000d*samples.length),allocated=(alloc.getThreadAllocatedBytes(thread)-bytes)/(double)samples.length;Arrays.sort(samples);
        times.append(String.format(Locale.ROOT,"%s,%s,%d,%d,%s,%.0f,%.2f,%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",scope,u.type.name,state,n,advanced,p,alpha,names[mode],block,samples[128]/1000d,samples[243]/1000d,samples[253]/1000d,samples[0]/1000d,samples[255]/1000d,complete,allocated));
    }
    private void micro() throws Throwable {
        Batch old=Core.batch;Mat p=new Mat(Draw.proj()),t=new Mat(Draw.trans());float time=Time.time;Time.time=3.1415927f;Draw.flush();target.begin(Color.clear);
        try{
            Unit c=units.first();pose(c,2);configure(batch);long until=System.nanoTime()+2_000_000_000L;
            while(System.nanoTime()<until)for(UnitProjection r:renderers)packet(r,c,11,true,64,1,true);Core.gl.glFinish();
            int[] candidates=screening?new int[]{2,3,4}:new int[]{1};
            for(int type=0;type<3;type++)for(int state:new int[]{0,1,2})for(boolean full:new boolean[]{false,true})for(int n:new int[]{1,11}){
                Unit u=units.get(type*11);pose(u,state);configure(batch);
                for(int mode:screening?new int[]{0,2,3,4}:new int[]{0,1})diagnostic("projection",u,state,n,full,64,mode);
                for(int candidate:candidates)for(int block=0;block<4;block++){int mode=block==0||block==3?0:candidate;timed("GL-ABBA",u,state,n,full,64,1,mode,block,batch);}
                for(int mode:screening?new int[]{0,2,3,4}:new int[]{0,1})timed("noGL",u,state,n,full,64,1,mode,0,noGL);
            }
            pose(c,7);for(float size:new float[]{16,150})for(float alpha:new float[]{1,.32f})for(int block=0;block<4;block++)timed("size-ABBA",c,7,11,true,size,alpha,block==0||block==3?0:screening?2:1,block,batch);
            prepareTimes();emptyFlush(old);gpuTimes();
        }finally{Draw.flush();target.end();Core.batch=old;Draw.proj(p);Draw.trans(t);Time.time=time;}
        save();System.out.println("Stage A micro complete");
    }
    private void diagnostic(String scope,Unit u,int state,int n,boolean full,float p,int mode){
        configure(batch);packet(renderers[mode],u,n,full,p,1,true);batch.resetCounts();batch.count=true;
        try(var gl=new StageABatch.GLCounts()){
            packet(renderers[mode],u,n,full,p,1,true);
            counts.append(String.format(Locale.ROOT,"%s,%s,%d,%d,%s,%.0f,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",scope,u.type.name,state,n,full,p,names[mode],batch.quads,batch.zeroAlpha,batch.flushes,batch.nonempty,batch.blendChanges,gl.draws,gl.textures,gl.scissors,gl.uploads));
        }finally{batch.count=false;}
    }
    private void prepareTimes() throws Throwable {
        Unit u=units.first();pose(u,2);configure(noGL);StringBuilder result=new StringBuilder("variant,block,p50-ns,p95-ns,p99-ns,B-per-call\n");
        for(int pass=0;pass<6;pass++){
            int mode=new int[]{0,2,3,3,2,0}[pass];UnitProjection r=renderers[mode];
            var method=r.getClass().getDeclaredMethod("prepare",Unit.class,ProjectionPlan.class,boolean.class);method.setAccessible(true);
            MethodHandle handle=MethodHandles.lookup().unreflect(method).bindTo(r);ProjectionPlan plan=hud.profiles.get(u.type).projectionPlan;
            for(int i=0;i<20000;i++)handle.invokeExact(u,plan,true);
            long[] samples=new long[2000];long bytes=alloc.getThreadAllocatedBytes(thread);
            for(int i=0;i<samples.length;i++){long start=System.nanoTime();for(int j=0;j<32;j++)handle.invokeExact(u,plan,true);samples[i]=System.nanoTime()-start;}
            bytes=alloc.getThreadAllocatedBytes(thread)-bytes;Arrays.sort(samples);
            result.append(String.format(Locale.ROOT,"%s,%d,%.3f,%.3f,%.3f,%.3f%n",names[mode],pass,samples[1000]/32d,samples[1900]/32d,samples[1980]/32d,bytes/(2000d*32)));
        }out.child("prepare.csv").writeString(result.toString());
    }
    private void emptyFlush(Batch nativeBatch){
        configure(nativeBatch);Draw.flush();StringBuilder s=new StringBuilder("case,block,ns-per-iteration\n");
        for(int block=0;block<8;block++){boolean flush=block%4==1||block%4==2;long sum=0,start=System.nanoTime();for(int i=0;i<2_000_000;i++){if(flush)Draw.flush();sum+=i;}long elapsed=System.nanoTime()-start;emptySink=sum;s.append(String.format(Locale.ROOT,"%s,%d,%.3f%n",flush?"empty-flush":"sum-control",block,elapsed/2_000_000d));}
        out.child("empty-flush.csv").writeString(s.toString());
    }
    private void gpuTimes(){
        StringBuilder result=new StringBuilder("type,state,N,variant,p50-us,p95-us,p99-us\n");
        try{
            GL30 api=(GL30)Class.forName("arc.backend.sdl.SdlGL30").getConstructor().newInstance();IntBuffer value=Buffers.newIntBuffer(1),ids=Buffers.newIntBuffer(64);
            api.glGetQueryiv(0x88BF,0x8864,value);if(value.get(0)==0)throw new IllegalStateException("No timer");value.clear();api.glGetQueryiv(0x88BF,0x8865,value);if(value.get(0)!=0)throw new IllegalStateException("Nested timer");api.glGenQueries(64,ids);
            try{for(int type=0;type<3;type++)for(int state:new int[]{0,1,2})for(int n:new int[]{1,11})for(int mode:screening?new int[]{0,2,4}:new int[]{0,1}){
                Unit u=units.get(type*11);pose(u,state);configure(batch);for(int i=0;i<100;i++)packet(renderers[mode],u,n,true,64,1,true);Core.gl.glFinish();
                for(int i=0;i<64;i++){Gl.clear(Gl.colorBufferBit);api.glBeginQuery(0x88BF,ids.get(i));packet(renderers[mode],u,n,true,64,1,false);api.glEndQuery(0x88BF);}Core.gl.glFinish();long[] samples=new long[64];
                for(int i=0;i<64;i++){value.clear();api.glGetQueryObjectuiv(ids.get(i),0x8867,value);if(value.get(0)==0)throw new AssertionError("Unready query");value.clear();api.glGetQueryObjectuiv(ids.get(i),0x8866,value);samples[i]=Integer.toUnsignedLong(value.get(0));if(samples[i]==0xffffffffL)throw new AssertionError("Saturated query");}Arrays.sort(samples);
                result.append(String.format(Locale.ROOT,"%s,%d,%d,%s,%.3f,%.3f,%.3f%n",u.type.name,state,n,names[mode],samples[32]/1000d,samples[60]/1000d,samples[63]/1000d));
            }}finally{ids.position(0);api.glDeleteQueries(64,ids);}if(Core.gl.glGetError()!=0)throw new AssertionError("GPU query GL error");
        }catch(Throwable error){result.append("UNAVAILABLE,").append(error).append('\n');}out.child("gpu.csv").writeString(result.toString());
    }

    private Pixmap image(UnitProjection r,Unit u,float size,float alpha,Color bg){
        Batch old=Core.batch;Mat p=new Mat(Draw.proj()),t=new Mat(Draw.trans());Draw.flush();qualityTarget.begin(bg);
        try{configure(batch);Draw.proj(384,0,512,512);r.clear();packet(r,u,1,true,size,alpha,false);return ScreenUtils.getFrameBufferPixmap(0,0,512,512);}
        finally{Draw.flush();qualityTarget.end();Core.batch=old;Draw.proj(p);Draw.trans(t);}
    }
    private void quality(){
        float time=Time.time;Time.time=3.1415927f;
        try{for(int type=0;type<5;type++)for(int state=0;state<8;state++)for(float p:new float[]{16,64,150})for(float alpha:new float[]{1,.32f})for(Color bg:new Color[]{Color.clear,new Color(.4f,.4f,.4f,.3f)}){
            Unit u=units.get(type*11);pose(u,state);Pixmap expected=image(renderers[0],u,p,alpha,bg);
            try{for(int mode:screening?new int[]{2,3}:new int[]{1}){Pixmap actual=image(renderers[mode],u,p,alpha,bg);try{diff("projection",u.type.name,state,1,p,alpha,names[mode],expected,actual,true);}finally{actual.dispose();}}}finally{expected.dispose();}
        }}finally{Time.time=time;}save();System.out.println("Stage A pixel matrix complete");
    }
    private void customBlend(){
        Unit u=units.first();var part=(mindustry.entities.part.RegionPart)u.mounts[0].weapon.parts.first();Color color=part.color;Blending blend=part.blending;
        try{pose(u,2);part.color=new Color(1,0,0,0);for(Blending b:new Blending[]{Blending.normal,Blending.additive,new Blending(Gl.one,Gl.one),Blending.disabled}){
            part.blending=b;Pixmap expected=image(renderers[0],u,100,.32f,new Color(.4f,.4f,.4f,.3f));
            try{for(int mode:screening?new int[]{2,3,4}:new int[]{1}){Pixmap actual=image(renderers[mode],u,100,.32f,new Color(.4f,.4f,.4f,.3f));try{diff("custom-blend-"+(b==Blending.normal?"normal":b==Blending.additive?"additive":b==Blending.disabled?"disabled":"one-one"),u.type.name,2,1,100,.32f,names[mode],expected,actual,mode!=4);}finally{actual.dispose();}}}finally{expected.dispose();}
        }}finally{part.color=color;part.blending=blend;}
    }
    private void callerContexts(){
        Unit u=units.first();pose(u,0);Batch old=Core.batch;Core.batch=batch;
        Shader shader=new Shader(
            "attribute vec4 a_position;attribute vec4 a_color;attribute vec2 a_texCoord0;uniform mat4 u_projTrans;varying vec4 v_color;varying vec2 v_uv;void main(){v_color=a_color;v_uv=a_texCoord0;gl_Position=u_projTrans*a_position;}",
            "varying vec4 v_color;varying vec2 v_uv;uniform sampler2D u_texture;void main(){vec4 t=texture2D(u_texture,v_uv);gl_FragColor=vec4(v_color.rgb*t.rgb,t.a);}");
        try{
            Draw.shader(shader);contextPixels("caller-shader-ignores-alpha",u);
            Draw.shader();Gl.blendEquationSeparate(0x8007,0x8007);contextPixels("caller-blend-MIN",u);
            Gl.blendEquationSeparate(0x8008,0x8008);contextPixels("caller-blend-MAX",u);
        }finally{Gl.blendEquationSeparate(0x8006,0x8006);Draw.shader();Core.batch=old;shader.dispose();}
    }
    private void contextPixels(String scope,Unit u){
        Color bg=new Color(.4f,.4f,.4f,.3f);Pixmap expected=image(renderers[0],u,100,.32f,bg);
        try{for(int mode:new int[]{1,4}){Pixmap actual=image(renderers[mode],u,100,.32f,bg);try{diff(scope,u.type.name,0,1,100,.32f,names[mode],expected,actual,mode==1);}finally{actual.dispose();}}}finally{expected.dispose();}
    }
    private void diff(String scope,String type,int state,int n,float p,float alpha,String variant,Pixmap a,Pixmap b,boolean exact){
        long changed=0;int rgb=0,opacity=0;
        for(int y=0;y<a.height;y++)for(int x=0;x<a.width;x++){int av=a.get(x,y),bv=b.get(x,y);if(av!=bv)changed++;opacity=Math.max(opacity,Math.abs((av&255)-(bv&255)));for(int shift=8;shift<=24;shift+=8)rgb=Math.max(rgb,Math.abs((av>>>shift&255)-(bv>>>shift&255)));}
        pixels.append(String.format(Locale.ROOT,"%s,%s,%d,%d,%.3f,%.2f,%s,%d,%d,%d%n",scope,type,state,n,p,alpha,variant,changed,rgb,opacity));
        if(exact&&changed!=0)throw new AssertionError("RGBA mismatch "+scope+" "+type+" "+state+" "+variant+" "+changed);
    }
    private void assign(int saved){
        for(int g=0;g<10;g++){Vars.control.input.controlGroups[g]=new IntSeq();if(g<saved)Vars.control.input.controlGroups[g].add(units.get(g).id);}
        hud.model.reconcile(Vars.control.input.controlGroups,id->mindustry.gen.Groups.unit.getByID(id),units,Team.sharded,true);hud.model.sample(Team.sharded,hud.settings,1);
    }
    private void hudMatrix() throws Exception {
        int width=Core.graphics.getWidth(),height=Core.graphics.getHeight();if(Core.scene.getWidth()!=width||Core.scene.getHeight()!=height)throw new AssertionError("Scene size mismatch");
        FrameBuffer f=new FrameBuffer(width,height);Batch old=Core.batch;Mat p=new Mat(Draw.proj()),t=new Mat(Draw.trans());float time=Time.time;Time.time=3.1415927f;
        try{for(int heatState=0;heatState<3;heatState++){
            for(Unit u:units)pose(u,heatState);
            for(int config=0;config<48;config++){
            float resize=new float[]{.25f,1,3}[config/16];setResize(resize);
            int saved=config/12%2==0?1:10;assign(saved);int direction=config%4;float scale=new float[]{.75f,1,2}[config/4%3];
            hud.settings.vertical=direction<2;hud.settings.reverse=direction%2!=0;hud.settings.internalProjection=config/24==0;
            hud.settings.projection=true;hud.settings.advanced=true;hud.settings.length=Math.max(160,Math.min(width,height)*1.2f);hud.settings.breadth=Math.max(80,Math.min(width,height)*.65f);
            hud.settings.occupancy=config%9==0?0:config%9==1?3:1;hud.settings.spaceMode=ModSettings.SpaceMode.values()[config%3];hud.settings.freeSpaceMode=ModSettings.FreeSpaceMode.values()[config/3%3];hud.settings.collapse=true;hud.editing=config%2!=0;
            arc.scene.ui.layout.Scl.setProduct(scale);hud.layout.rebuild(hud.settings,width,height,scale,false);hud.setBounds(.37f,.43f,hud.layout.width,hud.layout.height);
            for(var box:hud.layout.boxes){box.expansion=config%5==0?.4f:1;hud.layout.animate(box,hud.settings,false,false,0);}
            Pixmap expected=hudImage(f,width,height,0,true);
            try{for(int mode:screening?new int[]{2,3}:new int[]{1}){Pixmap actual=hudImage(f,width,height,mode,false);try{diff("HUD-"+width,"config-"+config,heatState,saved,resize,hud.editing?.32f:1,names[mode]+"+E",expected,actual,true);}finally{actual.dispose();}}}finally{expected.dispose();}
            if(config==23||config==47)nestedClips(f,width,height,config);
            if(config==0||config==23||config==47){setResize(1);
                if(clippingOnly){for(int block=0;block<6;block++){int policy=new int[]{0,1,2,2,1,0}[block];StageABatch.keepLeading=policy!=2;hudTimed(f,width,height,config,heatState,policy==0?0:1,block);}StageABatch.keepLeading=true;}
                else for(int mode:screening?new int[]{2,3}:new int[]{1})for(int block=0;block<4;block++)hudTimed(f,width,height,config,heatState,block==0||block==3?0:mode,block);
            }
        }}}finally{setResize(1);Draw.flush();Core.batch=old;Draw.proj(p);Draw.trans(t);Time.time=time;arc.scene.ui.layout.Scl.setProduct(1);hudProjection.set(hud,oldHudProjection);StageABatch.baselineClips=true;f.dispose();}
        save();System.out.println("Stage A HUD "+width+"x"+height+" complete");
    }
    private void setResize(float resize){for(Unit u:units)hud.profiles.get(u.type).resize=resize;}
    private void nestedClips(FrameBuffer f,int w,int h,int config) throws Exception {
        int[] step={0};
        Shader custom=new Shader(
            "attribute vec4 a_position;attribute vec4 a_color;attribute vec2 a_texCoord0;uniform mat4 u_projTrans;varying vec4 v_color;varying vec2 v_uv;void main(){v_color=a_color;v_uv=a_texCoord0;gl_Position=u_projTrans*a_position;}",
            "varying vec4 v_color;varying vec2 v_uv;uniform sampler2D u_texture;uniform float u_step;void main(){vec4 t=texture2D(u_texture,v_uv);gl_FragColor=vec4(v_color.rgb*t.rgb*u_step,t.a);}"){
            @Override public void apply(){setUniformf("u_step",.5f+.03f*(step[0]++%13));}
        };
        float[] widths=new float[hud.layout.boxes.length];for(int i=0;i<widths.length;i++)widths[i]=hud.layout.boxes[i].content.width;
        try{for(int shader=0;shader<2;shader++)for(int parent=0;parent<4;parent++){
            for(int i=0;i<widths.length;i++)hud.layout.boxes[i].content.width=parent==3?.4f:widths[i];
            Core.batch=batch;Draw.shader(shader==0?null:custom);step[0]=0;
            Pixmap expected=nestedImage(f,w,h,true,parent);String before=lastClipTrace;step[0]=0;
            try{Pixmap actual=nestedImage(f,w,h,false,parent);try{
                diff("parent-clip-"+w+"-"+parent+"-shader"+shader,"config-"+config,config,11,0,.32f,"production+E",expected,actual,true);
                if(!before.equals(lastClipTrace))throw new AssertionError("Nonempty draw/state sequence changed at clip boundary");
                clipping.append(String.format(Locale.ROOT,"%d,%d,%d,%d,%d,true%n",w,config,parent,shader,before.chars().filter(c->c==';').count()));
            }finally{actual.dispose();}}finally{expected.dispose();}
        }}finally{for(int i=0;i<widths.length;i++)hud.layout.boxes[i].content.width=widths[i];Draw.shader();custom.dispose();}
    }
    private Pixmap nestedImage(FrameBuffer f,int w,int h,boolean before,int parent) throws Exception {
        configure(batch);Draw.proj(0,0,w,h);hudProjection.set(hud,renderers[before?0:1]);StageABatch.baselineClips=before;
        f.begin(new Color(.12f,.12f,.12f,.4f));
        arc.math.geom.Rect bounds=parent==0?new arc.math.geom.Rect(.4f,.4f,w-1.2f,h-1.2f):parent==1?new arc.math.geom.Rect(w*.25f,h*.25f,w*.4f,h*.4f):new arc.math.geom.Rect(w+10,h+10,1,1);
        boolean pushed=ScissorStack.push(bounds);if(!pushed)throw new AssertionError("Test parent clip rejected");
        try{
            // Pending caller vertices must keep their original submit/scissor boundary even on rejection.
            try(var gl=new StageABatch.GLCounts(true)){
                Draw.color(Color.orange,.5f);Draw.rect(Core.atlas.white(),w*.5f,h*.5f,20,20);hud.draw();Draw.flush();lastClipTrace=gl.drawStates.toString();
            }
            return ScreenUtils.getFrameBufferPixmap(0,0,w,h);
        }finally{ScissorStack.pop();f.end();}
    }
    private Pixmap hudImage(FrameBuffer f,int w,int h,int mode,boolean oldClips) throws Exception {
        configure(batch);Draw.proj(0,0,w,h);hudProjection.set(hud,renderers[mode]);StageABatch.baselineClips=oldClips;f.begin(new Color(.12f,.12f,.12f,.4f));
        try{hud.draw();Draw.flush();return ScreenUtils.getFrameBufferPixmap(0,0,w,h);}finally{Draw.flush();f.end();}
    }
    private void hudTimed(FrameBuffer f,int w,int h,int config,int heatState,int mode,int block) throws Exception {
        configure(batch);Draw.proj(0,0,w,h);hudProjection.set(hud,renderers[screening?mode:1]);StageABatch.baselineClips=mode==0;f.begin(Color.clear);
        String label=names[mode]+(clippingOnly&&mode!=0?(StageABatch.keepLeading?"-post-only":"-both"):"");
        try{
            if(block<=2){batch.resetCounts();batch.count=true;try(var gl=new StageABatch.GLCounts()){hud.draw();Draw.flush();counts.append(String.format(Locale.ROOT,"HUD-%d,config-%d,%d,%d,true,0,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",w,config,heatState,hud.layout.activeCount,label,batch.quads,batch.zeroAlpha,batch.flushes,batch.nonempty,batch.blendChanges,gl.draws,gl.textures,gl.scissors,gl.uploads));}finally{batch.count=false;}}
            // Time the actual game SpriteBatch. Counting subclasses/proxies remain diagnostic only.
            configure(nativeTiming);Draw.proj(0,0,w,h);
            for(int i=0;i<200;i++){Gl.clear(Gl.colorBufferBit);hud.draw();Draw.flush();}Core.gl.glFinish();long[] samples=new long[256];long bytes=alloc.getThreadAllocatedBytes(thread),total=System.nanoTime();
            for(int i=0;i<samples.length;i++){long start=System.nanoTime();Gl.clear(Gl.colorBufferBit);hud.draw();Draw.flush();samples[i]=System.nanoTime()-start;}Core.gl.glFinish();double complete=(System.nanoTime()-total)/256000d,allocated=(alloc.getThreadAllocatedBytes(thread)-bytes)/256d;Arrays.sort(samples);
            times.append(String.format(Locale.ROOT,"HUD-%d,config-%d,%d,%d,true,0,%.2f,%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",w,config,heatState,hud.layout.activeCount,hud.editing?.32f:1,label,block,samples[128]/1000d,samples[243]/1000d,samples[253]/1000d,samples[0]/1000d,samples[255]/1000d,complete,allocated));
        }finally{Draw.flush();f.end();}
    }
    private void setupFrames(int count) throws Exception {
        Vars.state.teams.updateTeamStats();int have=Team.sharded.data().units.size;for(int i=have;i<count;i++)UnitTypes.dagger.spawn(Team.sharded,80+i%50*12,80+i/50*12);Vars.state.teams.updateTeamStats();if(Team.sharded.data().units.size!=count)throw new AssertionError("Unit count");
        for(int g=0;g<10;g++){Vars.control.input.controlGroups[g]=new IntSeq();for(int i=g;i<count-10;i+=10)Vars.control.input.controlGroups[g].add(Team.sharded.data().units.get(i).id);}
        for(Unit u:units)pose(u,2);hud.settings.vertical=true;hud.settings.reverse=false;hud.settings.spaceMode=ModSettings.SpaceMode.PROPORTIONAL;hud.settings.freeSpaceMode=ModSettings.FreeSpaceMode.LIMITED;hud.settings.length=640;hud.settings.breadth=240;hud.settings.xFraction=0;hud.settings.yFraction=.5f;hud.settings.collapse=false;hud.settings.internalProjection=false;hud.settings.occupancy=1;hud.editing=false;
        Vars.state.set(mindustry.core.GameState.State.paused);Vars.control.input.commandMode=true;frameBlock=frameSample=warm=0;frameMode(0);
    }
    private void frameMode(int mode) throws Exception {
        StageABatch.suppressHud=mode==0;StageABatch.measureHud=true;StageABatch.baselineClips=mode!=3;hud.settings.showBuild=true;hud.settings.advanced=mode>=2;hudProjection.set(hud,renderers[mode==3?1:0]);hud.changed();StageABatch.hudNanos=0;
    }
    private void frame(long elapsed) throws Exception {
        if(++warm<=50)return;if(hud.layout.activeCount!=11)throw new AssertionError("Incomplete frame fixture");frameTimes[frameSample]=elapsed;hudTimes[frameSample]=StageABatch.hudNanos;if(++frameSample<120)return;
        HashSet<Integer> representatives=new HashSet<>();for(var box:hud.layout.boxes)if(box.group.representative!=null)representatives.add(box.group.representative.unit.id);Arrays.sort(frameTimes);Arrays.sort(hudTimes);
        frames.append(String.format(Locale.ROOT,"%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",scene==0?500:1000,frameBlock,modes[frameBlock],hud.layout.activeCount,representatives.size(),frameTimes[60]/1000d,frameTimes[114]/1000d,frameTimes[118]/1000d,frameTimes[0]/1000d,frameTimes[119]/1000d,hudTimes[60]/1000d,hudTimes[114]/1000d));
        frameBlock++;frameSample=warm=0;if(frameBlock>=modes.length){if(scene++==0)setupFrames(1000);else{StageABatch.suppressHud=StageABatch.measureHud=false;stage=6;}}else frameMode(modes[frameBlock]);save();
    }
    private void save(){out.child("times.csv").writeString(times.toString());out.child("counts.csv").writeString(counts.toString());out.child("pixels.csv").writeString(pixels.toString());out.child("frames.csv").writeString(frames.toString());out.child("clip-sequences.csv").writeString(clipping.toString());}
    private void dispose(){target.dispose();qualityTarget.dispose();batch.dispose();noGL.dispose();}
}
