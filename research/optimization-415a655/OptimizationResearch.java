package controlgroups;

import arc.Core;
import arc.files.Fi;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.FrameBuffer;
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
import java.lang.management.ManagementFactory;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.util.*;
import java.util.function.LongConsumer;

/** EXPERIMENT ONLY. Executed inside the isolated mod loader, with separate world/settings. */
public final class OptimizationResearch implements LongConsumer {
    private final Fi out;
    private final GroupsHud hud;
    private final UnitProjection stock;
    private final ResearchProjection proto;
    private final ResearchBatch.Counting client = new ResearchBatch.Counting(false), vbo = new ResearchBatch.Counting(true);
    private final ResearchBatch.Null noGL = new ResearchBatch.Null();
    private final ResearchQueue queue = new ResearchQueue();
    private ResearchBatch.Unified pma, channels, dual, fetch;
    private boolean clearPacket;
    private final Seq<Unit> samples = new Seq<>();
    private final StringBuilder times = new StringBuilder("scope,type,state,groups,advanced,pixels,variant,block,median-us,p95-us,p99-us,min-us,max-us,completed-us-per-packet,allocated-B-per-packet\n");
    private final StringBuilder counts = new StringBuilder("scope,type,state,groups,advanced,variant,quads,zero-alpha,flush-requests,nonempty,blend-switches,textures,gl-draws,gl-buffer-uploads,gl-upload-bytes,gl-texture-binds,gl-blend-functions,gl-scissors,gl-shaders,gl-attrib-pointers\n");
    private final StringBuilder parity = new StringBuilder("scope,type,state,variant,background,alpha,changed-pixels,max-rgb,max-alpha,mean-rgb\n");
    private final com.sun.management.ThreadMXBean allocations = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private final long thread = Thread.currentThread().getId();
    private final FrameBuffer target = new FrameBuffer(1280,512);
    private final FrameBuffer qualityTarget = new FrameBuffer(512,512);
    private final Mat identity = new Mat();
    private int stage, warm;
    private long frameEpoch;
    private Field hudProjection;
    private Object originalHudRenderer;
    private int frameBlock, frameSample, sceneIndex;
    private final long[] gameSamples=new long[120],hudSamples=new long[120];
    private final int[] frameModes={0,1,2,3,4,4,3,2,1,0};
    private final StringBuilder frames=new StringBuilder("scene,block,mode,p50-frame-us,p95-frame-us,p99-frame-us,min-us,max-us,p50-hud-us,p95-hud-us,over-8.33ms,over-16.67ms\n");
    private final StringBuilder frameFixture=new StringBuilder("scene,block,mode,active-groups,unique-representatives,conquer-representatives,min-P,max-P,command-mode,suppress-HUD-draw\n");

    public OptimizationResearch(Fi out) {
        this.out=out; hud=(GroupsHud)Core.scene.find("control-groups-hud");
        stock=new UnitProjection(hud.profiles); proto=new ResearchProjection(hud.profiles);
    }
    @Override public void accept(long elapsed) {
        try {
            switch(stage) {
                case 0 -> { setup(); stage=1; }
                case 1 -> { if (++warm>=90) {
                    if(Boolean.getBoolean("research.frameOnly")){customBlendProof();setupFrameScene(500);stage=6;warm=-200;}
                    else if(Boolean.getBoolean("research.proofOnly")){driftProof();customBlendProof();save();stage=7;}
                    else{micro();quality();driftProof();textureProbe();lodProbe();cacheProbe();queueProbe();hudExperiments();stateProof();save();stage=2;warm=0;}
                } }
                case 2 -> { if(++warm==2){Core.graphics.setWindowSize(640,480);stage=3;warm=0;} }
                case 3 -> { if(++warm==3){hudExperiments();Core.graphics.setWindowSize(320,240);stage=4;warm=0;} }
                case 4 -> { if(++warm==3){hudExperiments();Core.graphics.setWindowSize(1280,900);stage=5;warm=0;} }
                case 5 -> { if(++warm==3){setupFrameScene(500);stage=6;warm=0;} }
                case 6 -> { frameTests(elapsed); }
                default -> { save(); dispose(); Core.app.exit(); }
            }
        } catch(Throwable error) {
            save(); out.child("FAILED.txt").writeString(arc.util.Strings.getStackTrace(error));
            error.printStackTrace(); System.exit(2);
        }
    }
    private void setup() {
        Core.settings.put("fpscap",0); Core.settings.put("effects",false); Core.settings.put("commandmodehold",false);
        Vars.logic.reset(); Vars.state.rules=new mindustry.game.Rules();
        Vars.world.loadGenerator(96,96,tiles->{tiles.fill();tiles.eachTile(t->t.setFloor(Blocks.stone.asFloor()));tiles.getn(20,20).setBlock(Blocks.coreShard,Team.sharded,0);});
        Vars.state.rules.waves=false; Vars.state.rules.unitCap=20000; Vars.state.rules.infiniteResources=true;
        Vars.logic.play(); Vars.player.team(Team.sharded); Vars.player.unit(UnitTypes.alpha.spawn(Team.sharded,160,160));
        Vars.state.set(mindustry.core.GameState.State.paused);
        Vars.control.input.commandMode=true;
        for (UnitType t:new UnitType[]{UnitTypes.conquer,UnitTypes.oct,UnitTypes.disrupt,UnitTypes.reign,UnitTypes.eclipse})
            for(int i=0;i<11;i++) samples.add(t.spawn(Team.sharded,450,450));
        hud.settings.collapse=false; hud.settings.collapseBuild=hud.settings.collapseCommand=false;
        hud.settings.unassigned=true; hud.settings.showBuild=true; hud.settings.editorSchematic=false;
        Core.scene.act(1f/60);
        String caps="HEAD=415a6552eb0cc3d9cdff4a59d164b98f98049a47\nJava="+System.getProperty("java.version")+"\nGL="+Core.gl.glGetString(Gl.version)+"\nRenderer="+Core.gl.glGetString(Gl.renderer)+"\nCore.gl30="+(Core.gl30!=null)+"\n";
        caps+="Extensions="+Core.gl.glGetString(Gl.extensions)+"\n";
        IntBuffer value=Buffers.newIntBuffer(4); Core.gl.glGetIntegerv(0x88FC,value); caps+="MaxDualSource="+value.get(0)+" error="+Core.gl.glGetError()+"\n";
        pma=new ResearchBatch.Unified(0,false); channels=new ResearchBatch.Unified(1,false);
        try { dual=new ResearchBatch.Unified(2,false); caps+="Dual shader compiled=true\n"; }
        catch(RuntimeException e) { caps+="Dual shader compiled=false: "+e+"\n"; }
        try {fetch=new ResearchBatch.Unified(3,false);caps+="Fetch shader compiled=true\n";}
        catch(RuntimeException e){caps+="Fetch shader compiled=false: "+e+"\n";}
        out.child("environment.txt").writeString(caps);
        System.out.println("Research environment initialized; dual="+(dual!=null));
        try {hudProjection=GroupsHud.class.getDeclaredField("projection");hudProjection.setAccessible(true);originalHudRenderer=hudProjection.get(hud);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private void pose(Unit u,int state) {
        u.rotation=state==7?45:90; u.elevation=u.type.flying?1:0; u.vel.setZero();
        u.health=u.maxHealth; u.hitTime=state==3?.75f:0; u.healTime=state==4?.8f:0;
        u.drownTime=state==5?.65f:0; u.lastDrownFloor=Blocks.deepwater.asFloor();
        u.shield=state==6?100:0; u.shieldAlpha=state==6?1:0;
        float p=state==0?0:state==1?.5f:1;
        for(var m:u.mounts) {m.rotation=state==7?90:0;m.warmup=m.heat=m.charge=m.recoil=m.smoothReload=p;m.reload=p*m.weapon.reload;if(m.recoils!=null)Arrays.fill(m.recoils,p);}
    }
    private void packet(UnitProjection renderer,Unit unit,int groups,boolean advanced,float pixels,float alpha) {
        if(clearPacket && !(Core.batch instanceof ResearchBatch.Null)) Gl.clear(Gl.colorBufferBit);
        if(renderer instanceof ResearchProjection r) r.beginFrame();
        for(int g=0;g<groups;g++) renderer.draw(unit,g,(g+.5f)*1280/groups,256,pixels,180,unit.type.hitSize,0,advanced,alpha);
        Draw.flush();
    }
    private void configure(Batch b) {
        Core.batch=b; Draw.reset(); Draw.scl(1); Draw.proj(0,0,1280,512); Draw.trans(identity); Draw.blend();
        if(b instanceof ResearchBatch.Unified u) u.beginScope();
    }
    private void micro() throws Throwable {
        Batch original=Core.batch; Mat projection=new Mat(Draw.proj()),transform=new Mat(Draw.trans()); float time=Time.time;
        Draw.flush(); target.begin(Color.clear); Time.time=3.1415927f;
        clearPacket=true;
        try {
            Unit initial=samples.first();pose(initial,2);configure(client);proto.variant=1;
            long warmUntil=System.nanoTime()+2_000_000_000L;
            while(System.nanoTime()<warmUntil){packet(stock,initial,11,true,64,1);packet(proto,initial,11,true,64,1);}
            Core.gl.glFinish();
            for(int t=0;t<3;t++) for(int state:new int[]{0,1,2}) for(boolean advanced:new boolean[]{false,true}) for(int n:new int[]{1,11}) {
                Unit unit=samples.get(t*11); pose(unit,state);
                // Separate diagnostic pass: counts are never enabled in timing blocks.
                configure(client); client.count=true; client.resetCounts();
                try(var gl=new ResearchBatch.GLCounts()) {
                    packet(stock,unit,n,advanced,64,1);
                    counts.append(String.format(Locale.ROOT,"isolated,%s,%d,%d,%s,stock,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",unit.type.name,state,n,advanced,
                        client.quads,client.zeroAlpha,client.flushes,client.nonempty,client.blends,client.textures,gl.draws,gl.uploads,gl.uploadBytes,gl.textureBinds,gl.blendFunctions,gl.scissors,gl.shaders,gl.attribPointers));
                } finally {client.count=false;}
                configure(noGL); timed("noGL",unit,state,n,advanced,64,"stock",stock,noGL,350,0);
                configure(client); timed("isolated",unit,state,n,advanced,64,"stock",stock,client,200,0);
            }
            // ABBA, same state/size/scene, fresh completed GPU queue at each block.
            Unit conquer=samples.first();
            for(int state:new int[]{0,1,2,7}) for(int n:new int[]{1,11}) for(int candidate=0;candidate<7;candidate++) {
                pose(conquer,state);
                Batch b=switch(candidate){case 1->vbo;case 2->channels;case 3->dual;case 6->fetch;default->client;};if(b==null)continue;
                int flag=switch(candidate){case 0->1;case 4->3;case 5->7;default->1;};
                String label=switch(candidate){case 0->"skip";case 1->"skip+vbo";case 2->"skip+channels";case 3->"skip+dual";case 4->"skip+java";case 6->"skip+fetch";default->"skip+java+share";};
                if(candidate==3&&dual==null)continue;
                for(int block=0;block<4;block++) {
                    boolean base=block==0||block==3; proto.variant=flag;
                    configure(base?client:b);
                    timed("ABBA",conquer,state,n,true,64,base?"stock-for-"+label:label,base?stock:proto,base?client:b,128,block);
                }
            }
            // Size/fill pressure; no claim of GPU-only timing.
            for(float pixels:new float[]{16,150}) for(int kind=0;kind<3;kind++) {
                Batch b=kind==0?client:kind==1?channels:dual; if(b==null)continue;
                pose(conquer,7); proto.variant=1; configure(b);
                timed("size",conquer,7,11,true,pixels,kind==0?"stock":kind==1?"channels":"dual",kind==0?stock:proto,b,180,0);
            }
            // Explicit 2x2: A=skip invisible, B=dual source. No addition of separate percentages.
            if(dual!=null)for(int state:new int[]{0,2})for(int n:new int[]{1,11})for(int block=0;block<8;block++){
                int mode=new int[]{0,1,2,3,3,2,1,0}[block];pose(conquer,state);proto.variant=mode==1||mode==3?1:0;
                Batch b=mode>=2?dual:client;configure(b);
                timed("combination",conquer,state,n,true,64,new String[]{"stock","skip","dual-only","skip+dual"}[mode],mode==0?stock:proto,b,200,block);
            }
            for(int state:new int[]{0,2})for(int n:new int[]{1,11})for(int mode=0;mode<4;mode++){
                pose(conquer,state);proto.variant=new int[]{0,1,3,7}[mode];configure(noGL);
                timed("noGL-candidates",conquer,state,n,true,64,new String[]{"stock","skip","skip+java","skip+java+share"}[mode],mode==0?stock:proto,noGL,1000,0);
            }
            prepareOnly();
            variantCounts();
            gpuTimers();
        } finally {clearPacket=false;Draw.flush();target.end();Core.batch=original;Draw.proj(projection);Draw.trans(transform);Time.time=time;}
        save(); System.out.println("Research micro measurements complete");
    }
    private void timed(String scope,Unit unit,int state,int n,boolean advanced,float pixels,String label,UnitProjection r,Batch b,int size,int block) {
        for(int i=0;i<100;i++)packet(r,unit,n,advanced,pixels,1);
        if(!(b instanceof ResearchBatch.Null)) Core.gl.glFinish();
        long[] samples=new long[size]; long allocated=allocations.getThreadAllocatedBytes(thread),total=System.nanoTime();
        for(int i=0;i<size;i++) {
            long start=System.nanoTime(); packet(r,unit,n,advanced,pixels,1); samples[i]=System.nanoTime()-start;
        }
        if(!(b instanceof ResearchBatch.Null))Core.gl.glFinish();
        double completed=(System.nanoTime()-total)/(1000d*size),bytes=(allocations.getThreadAllocatedBytes(thread)-allocated)/(double)size;
        Arrays.sort(samples);
        times.append(String.format(Locale.ROOT,"%s,%s,%d,%d,%s,%.0f,%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",scope,unit.type.name,state,n,advanced,pixels,label,block,
            samples[size/2]/1000d,samples[(int)(size*.95)]/1000d,samples[(int)(size*.99)]/1000d,samples[0]/1000d,samples[size-1]/1000d,completed,bytes));
    }
    private void prepareOnly() throws Throwable {
        configure(noGL); Unit u=samples.first(); pose(u,2); packet(stock,u,1,true,64,1);
        var method=UnitProjection.class.getDeclaredMethod("prepare",Unit.class,ProjectionPlan.class,boolean.class);method.setAccessible(true);
        MethodHandle handle=MethodHandles.lookup().unreflect(method).bindTo(stock); ProjectionPlan plan=hud.profiles.get(u.type).projectionPlan;
        long[] elapsed=new long[1000];
        for(int i=0;i<10000;i++)handle.invokeExact(u,plan,true);
        long before=allocations.getThreadAllocatedBytes(thread);
        for(int i=0;i<elapsed.length;i++){long start=System.nanoTime();handle.invokeExact(u,plan,true);elapsed[i]=System.nanoTime()-start;}
        long bytes=allocations.getThreadAllocatedBytes(thread)-before; Arrays.sort(elapsed);
        out.child("prepare.txt").writeString(String.format(Locale.ROOT,"Actual Conquer prepare only: median/p95/p99 %.3f/%.3f/%.3f us; %.1f B/call. MethodHandle harness, no GL.%n",elapsed[500]/1000d,elapsed[950]/1000d,elapsed[990]/1000d,bytes/1000d));
    }
    private void gpuTimers() {
        StringBuilder report=new StringBuilder("type,state,groups,variant,counter-bits,p50-us,p95-us,p99-us,min-us,max-us\n");
        try {
            // Local stateless SDL wrapper; Core.gl30 stays null, retaining the measured VertexArray path.
            GL30 api=(GL30)Class.forName("arc.backend.sdl.SdlGL30").getConstructor().newInstance();
            IntBuffer value=Buffers.newIntBuffer(1);api.glGetQueryiv(0x88BF,0x8864,value);int bits=value.get(0);
            value.clear();api.glGetQueryiv(0x88BF,0x8865,value);
            if(bits==0||value.get(0)!=0)throw new IllegalStateException("Unavailable or nested timer");
            IntBuffer ids=Buffers.newIntBuffer(64);api.glGenQueries(64,ids);
            boolean oldClear=clearPacket;clearPacket=false;
            try {
                for(int t=0;t<3;t++)for(int state:new int[]{0,2})for(int n:new int[]{1,11})for(int mode=0;mode<5;mode++) {
                    Batch b=switch(mode){case 2->channels;case 3->dual;case 4->fetch;default->client;};if(b==null)continue;
                    Unit u=samples.get(t*11);pose(u,state);proto.variant=1;configure(b);
                    UnitProjection r=mode==0?stock:proto;
                    for(int i=0;i<100;i++)packet(r,u,n,true,64,1);Core.gl.glFinish();
                    for(int i=0;i<64;i++){Gl.clear(Gl.colorBufferBit);api.glBeginQuery(0x88BF,ids.get(i));packet(r,u,n,true,64,1);api.glEndQuery(0x88BF);}
                    Core.gl.glFinish();long[] elapsed=new long[64];
                    for(int i=0;i<64;i++){value.clear();api.glGetQueryObjectuiv(ids.get(i),0x8867,value);if(value.get(0)==0)throw new AssertionError("Query result not ready");value.clear();api.glGetQueryObjectuiv(ids.get(i),0x8866,value);elapsed[i]=Integer.toUnsignedLong(value.get(0));if(elapsed[i]==0xffffffffL)throw new AssertionError("Saturated query");}
                    Arrays.sort(elapsed);
                    report.append(String.format(Locale.ROOT,"%s,%d,%d,%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f%n",u.type.name,state,n,new String[]{"stock","skip","channels","dual","fetch"}[mode],bits,elapsed[32]/1000d,elapsed[60]/1000d,elapsed[63]/1000d,elapsed[0]/1000d,elapsed[63]/1000d));
                }
            }finally{clearPacket=oldClear;ids.position(0);api.glDeleteQueries(64,ids);}
            if(Core.gl.glGetError()!=0)throw new AssertionError("Timer GL error");
        } catch(Throwable failure){report.append("UNAVAILABLE,").append(failure).append('\n');}
        out.child("gpu-timers.csv").writeString(report.toString());
    }
    private void variantCounts() {
        StringBuilder report=new StringBuilder("state,groups,variant,plan-operations,quads,normal,additive,visible-normal,visible-additive,zero-alpha,flush-requests,nonempty,gl-draws,uploads,upload-bytes,blend-functions,texture-binds,ordered-runs\n");
        Unit u=samples.first();
        for(int state:new int[]{0,1,2,7})for(int n:new int[]{1,11})for(int mode=0;mode<8;mode++) {
            Batch b=switch(mode){case 2->vbo;case 3->channels;case 4->dual;case 5->fetch;default->client;};if(b==null)continue;
            pose(u,state);configure(b);proto.variant=mode==6?3:mode==7?7:1;
            UnitProjection r=mode==0?stock:proto;
            packet(r,u,n,true,64,1); // Warm texture/shader before counting.
            ResearchBatch.Counting c=b instanceof ResearchBatch.Counting cc?cc:null;
            if(c!=null){c.resetCounts();c.count=true;}
            try(var gl=new ResearchBatch.GLCounts()){
                packet(r,u,n,true,64,1);
                report.append(String.format(Locale.ROOT,"%d,%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s%n",state,n,new String[]{"stock","skip","skip+vbo","skip+channels","skip+dual","skip+fetch","skip+java","skip+share"}[mode],hud.profiles.get(u.type).projectionPlan.ground.length,
                    c==null?-1:c.quads,c==null?-1:c.normal,c==null?-1:c.additive,c==null?-1:c.visibleNormal,c==null?-1:c.visibleAdditive,c==null?-1:c.zeroAlpha,c==null?-1:c.flushes,c==null?-1:c.nonempty,gl.draws,gl.uploads,gl.uploadBytes,gl.blendFunctions,gl.textureBinds,c==null?"unified":c.runs()));
            }finally{if(c!=null)c.count=false;}
        }
        out.child("conquer-profile.csv").writeString(report.toString());
    }
    private void textureProbe(){
        // Synthetic page fragmentation, not a claim about an installed third-party UnitType.
        Unit u=samples.first();TextureRegion a=u.type.region,b=u.mounts[0].weapon.region;
        Texture oldA=a.texture,oldB=b.texture;Pixmap p1=new Pixmap(1,1),p2=new Pixmap(1,1);p1.fill(Color.orange);p2.fill(Color.cyan);
        Texture ta=new Texture(p1),tb=new Texture(p2);p1.dispose();p2.dispose();
        Batch old=Core.batch;Mat p=new Mat(Draw.proj()),t=new Mat(Draw.trans());float time=Time.time;Time.time=3.1415927f;
        StringBuilder report=new StringBuilder("groups,variant,gl-draws,texture-binds,blend-functions\n");
        try{
            a.texture=ta;b.texture=tb;pose(u,2);proto.variant=1;
            Pixmap reference=image(stock,client,u,100,.32f,Color.clear);
            try{for(int mode=0;mode<2;mode++){if(mode==1&&dual==null)continue;Pixmap actual=image(proto,mode==0?client:dual,u,100,.32f,Color.clear);try{diff("synthetic-three-textures",u.type.name,2,mode==0?"skip":"skip+dual","clear",.32f,reference,actual);}finally{actual.dispose();}}}finally{reference.dispose();}
            target.begin(Color.clear);clearPacket=true;
            try{for(int n:new int[]{1,11})for(int mode=0;mode<3;mode++){
                if(mode==2&&dual==null)continue;Batch batch=mode==2?dual:client;configure(batch);UnitProjection r=mode==0?stock:proto;
                try(var gl=new ResearchBatch.GLCounts()){packet(r,u,n,true,64,1);report.append(String.format(Locale.ROOT,"%d,%s,%d,%d,%d%n",n,new String[]{"stock","skip","skip+dual"}[mode],gl.draws,gl.textureBinds,gl.blendFunctions));}
                timed("synthetic-three-textures",u,2,n,true,64,new String[]{"stock","skip","skip+dual"}[mode],r,batch,200,0);
            }}finally{Draw.flush();target.end();clearPacket=false;}
        }finally{a.texture=oldA;b.texture=oldB;Core.batch=old;Draw.proj(p);Draw.trans(t);Time.time=time;ta.dispose();tb.dispose();}
        out.child("textures.csv").writeString(report.toString());
    }

    private void cacheProbe() {
        FrameBuffer cache=new FrameBuffer(512,512);Batch old=Core.batch;Mat p=new Mat(Draw.proj()),t=new Mat(Draw.trans());
        Blending premult=new Blending(Gl.one,Gl.oneMinusSrcAlpha,Gl.one,Gl.oneMinusSrcAlpha);
        Unit u=samples.first();float time=Time.time;Time.time=3.1415927f;
        try {
            for(int state:new int[]{0,2,4})for(float alpha:new float[]{1,.32f}) {
                pose(u,state);cache.begin(Color.clear);configure(client);Draw.proj(384,0,512,512);packet(stock,u,1,true,100,alpha);cache.end();
                TextureRegion cached=new TextureRegion(cache.getTexture());cached.flip(false,true);
                for(Color bg:new Color[]{Color.clear,new Color(.4f,.4f,.4f,.3f),Color.white}) {
                    Pixmap reference=image(stock,client,u,100,alpha,bg);
                    qualityTarget.begin(bg);configure(client);Draw.proj(384,0,512,512);Draw.color();Draw.mixcol();Draw.blend(premult);
                    Draw.rect(cached,640,256,512,512);Draw.flush();Pixmap actual=ScreenUtils.getFrameBufferPixmap(0,0,512,512);qualityTarget.end();Draw.blend();
                    try{diff("transparent-FBO-cache",u.type.name,state,"cache-premult",bg.toString(),alpha,reference,actual);}finally{reference.dispose();actual.dispose();}
                }
            }
            // Separate hit-only and refresh+hit cost. Includes target changes, excludes cache creation.
            StringBuilder timing=new StringBuilder("case,p50-us,p95-us,p99-us,min-us,max-us\n");
            pose(u,2);TextureRegion region=new TextureRegion(cache.getTexture());region.flip(false,true);
            for(boolean refresh:new boolean[]{false,true}) {
                long[] elapsed=new long[300];
                for(int i=-200;i<elapsed.length;i++) {
                    long start=System.nanoTime();
                    if(refresh){cache.begin(Color.clear);configure(client);Draw.proj(384,0,512,512);packet(stock,u,1,true,100,1);cache.end();}
                    qualityTarget.begin(Color.clear);configure(client);Draw.proj(384,0,512,512);Draw.blend(premult);Draw.rect(region,640,256,512,512);Draw.flush();qualityTarget.end();Draw.blend();
                    if(i>=0)elapsed[i]=System.nanoTime()-start;
                }
                Core.gl.glFinish();Arrays.sort(elapsed);timing.append(String.format(Locale.ROOT,"%s,%.3f,%.3f,%.3f,%.3f,%.3f%n",refresh?"refresh+display":"display-only",elapsed[150]/1000d,elapsed[285]/1000d,elapsed[297]/1000d,elapsed[0]/1000d,elapsed[299]/1000d));
            }
            out.child("cache-times.csv").writeString(timing.toString());
        }finally{Draw.flush();Core.batch=old;Draw.proj(p);Draw.trans(t);Time.time=time;cache.dispose();}
    }
    private void customBlendProof(){
        Unit u=samples.first();var part=(mindustry.entities.part.RegionPart)u.mounts[0].weapon.parts.first();
        Color color=part.color;Blending blend=part.blending;float time=Time.time;Time.time=3.1415927f;
        try{
            pose(u,2);part.color=new Color(1,0,0,0);part.blending=new Blending(Gl.one,Gl.one);proto.variant=1;
            Pixmap ref=image(stock,client,u,100,1,Color.clear),actual=image(proto,client,u,100,1,Color.clear);
            try{diff("custom-blend-zero-alpha",u.type.name,2,"UNSAFE-unconditional-skip","clear",1,ref,actual);}finally{ref.dispose();actual.dispose();}
        }finally{part.color=color;part.blending=blend;Time.time=time;}
    }
    private void driftProof() throws Exception {
        float time=Time.time;Time.time=3.1415927f;
        try{
            for(int type=0;type<3;type++)for(float angle:new float[]{45,135})for(int n:new int[]{1,11})for(float pixels:new float[]{64,150})for(float alpha:new float[]{1,.32f}){
                Unit u=samples.get(type*11);pose(u,7);u.rotation=angle;u.vel.set(1,1);
                Pixmap reference=driftImage(stock,client,u,n,pixels,alpha);
                try{for(int mode=0;mode<2;mode++){
                    if(mode==1&&dual==null)continue;proto.variant=mode==0?7:1;
                    Pixmap actual=driftImage(proto,mode==0?client:dual,u,n,pixels,alpha);
                    try{diff("max-drift-"+(int)angle+"-N"+n+"-P"+(int)pixels,u.type.name,7,mode==0?"skip+java+share":"skip+dual","gray",alpha,reference,actual);}finally{actual.dispose();}
                }}finally{reference.dispose();}
            }
        }finally{Time.time=time;}
    }
    private Pixmap driftImage(UnitProjection r,Batch b,Unit u,int n,float pixels,float alpha) throws Exception {
        Batch old=Core.batch;Mat p=new Mat(Draw.proj()),t=new Mat(Draw.trans());
        Draw.flush();target.begin(new Color(.4f,.4f,.4f,.3f));
        try{
            configure(b);r.clear();
            Field x=r.getClass().getDeclaredField("driftX"),y=r.getClass().getDeclaredField("driftY");x.setAccessible(true);y.setAccessible(true);
            float[] dx=(float[])x.get(r),dy=(float[])y.get(r);for(int g=0;g<n;g++){dx[g]=(g%2==0?1:-1)*180*UnitProjection.DRIFT_FRACTION;dy[g]=((g&2)==0?1:-1)*180*UnitProjection.DRIFT_FRACTION;}
            packet(r,u,n,true,pixels,alpha);return ScreenUtils.getFrameBufferPixmap(0,0,1280,512);
        }finally{Draw.flush();target.end();Core.batch=old;Draw.proj(p);Draw.trans(t);}
    }
    private void lodProbe(){
        float time=Time.time;Time.time=3.1415927f;Batch old=Core.batch;Mat p=new Mat(Draw.proj()),t=new Mat(Draw.trans());
        try{
            for(int type=0;type<5;type++)for(int state:new int[]{0,2})for(float pixels:new float[]{16,64,150})for(float alpha:new float[]{1,.32f}){
                Unit u=samples.get(type*11);pose(u,state);Pixmap a=image(stock,client,u,pixels,alpha,Color.clear,true),b=image(stock,client,u,pixels,alpha,Color.clear,false);
                try{diff("LOD-basic-P"+(int)pixels,u.type.name,state,"basic-versus-full","clear",alpha,a,b);}finally{a.dispose();b.dispose();}
            }
            target.begin(Color.clear);clearPacket=true;
            try{
                for(int type=0;type<3;type++)for(float pixels:new float[]{16,64,150})for(int block=0;block<4;block++){
                    Unit u=samples.get(type*11);pose(u,2);configure(client);boolean full=block==0||block==3;
                    timed("LOD",u,2,11,full,pixels,full?"full":"basic",stock,client,200,block);
                }
            }finally{Draw.flush();target.end();clearPacket=false;}
        }finally{Core.batch=old;Draw.proj(p);Draw.trans(t);Time.time=time;}
    }
    private void queueProbe() {
        Batch old=Core.batch;Mat p=new Mat(Draw.proj()),t=new Mat(Draw.trans());float time=Time.time;Time.time=3.1415927f;
        StringBuilder report=new StringBuilder("pixels,block,variant,disjoint,gl-draws,p50-us,p95-us,p99-us,min-us,max-us,complete-us,allocated-B\n");
        try {
            Unit u=samples.first();pose(u,7);proto.variant=1;
            for(float pixels:new float[]{32,64,150}) {
                target.begin(Color.clear);configure(client);stock.clear();packet(stock,u,11,true,pixels,1);Pixmap ref=ScreenUtils.getFrameBufferPixmap(0,0,1280,512);target.end();
                try {
                    for(int mode=0;mode<3;mode++) {
                        target.begin(Color.clear);configure(client);queue.capture(proto,u,11,pixels,true);queue.replay(mode!=0,mode!=2);Pixmap actual=ScreenUtils.getFrameBufferPixmap(0,0,1280,512);target.end();
                        try{diff("queue",u.type.name,7,new String[]{"serial-capture","guarded-phases","UNSAFE-phases"}[mode],"clear",1,ref,actual);}finally{actual.dispose();}
                    }
                }finally{ref.dispose();}
                for(int block=0;block<14;block++) {
                    int mode=block<7?block:13-block;
                    if(mode>=5&&dual==null)continue;
                    proto.variant=mode==2||mode==3?0:1;
                    target.begin(Color.clear);configure(mode>=5?dual:client);
                    long calls;
                    try(var gl=new ResearchBatch.GLCounts()){queuePacket(mode,u,pixels);calls=gl.draws;}
                    for(int i=0;i<200;i++){Gl.clear(Gl.colorBufferBit);queuePacket(mode,u,pixels);}
                    Core.gl.glFinish();long[] values=new long[300];long bytes=allocations.getThreadAllocatedBytes(thread),total=System.nanoTime();
                    for(int i=0;i<300;i++){long start=System.nanoTime();Gl.clear(Gl.colorBufferBit);queuePacket(mode,u,pixels);values[i]=System.nanoTime()-start;}
                    Core.gl.glFinish();double complete=(System.nanoTime()-total)/300000d,allocated=(allocations.getThreadAllocatedBytes(thread)-bytes)/300d;
                    Draw.flush();target.end();Arrays.sort(values);
                    report.append(String.format(Locale.ROOT,"%.0f,%d,%s,%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",pixels,block,new String[]{"stock","skip-direct","serial-capture","guarded-phases","skip+guarded-phases","skip+dual-direct","skip+dual+guarded-phases"}[mode],queue.disjoint,calls,values[150]/1000d,values[285]/1000d,values[297]/1000d,values[0]/1000d,values[299]/1000d,complete,allocated));
                }
            }
        }finally{Core.batch=old;Draw.proj(p);Draw.trans(t);Time.time=time;}
        out.child("queue.csv").writeString(report.toString());
    }
    private void queuePacket(int mode,Unit u,float pixels){
        if(mode==0)packet(stock,u,11,true,pixels,1);
        else if(mode==1||mode==5)packet(proto,u,11,true,pixels,1);
        else {queue.capture(proto,u,11,pixels,true);queue.replay(mode>=3,true);}
    }

    private void stateProof() throws Exception {
        Batch old=Core.batch;Mat oldP=new Mat(Draw.proj()),oldT=new Mat(Draw.trans());
        Object renderer=hudProjection.get(hud);Batch backend=ResearchBatch.hudBackend;
        boolean parentClip=false;
        try {
            Core.batch=client;Draw.proj(0,0,1280,900);Draw.trans(identity);Draw.color(Color.pink,.45f);Draw.mixcol(Color.green,.2f);Draw.scl(1.2f,.8f);Draw.z(123);Lines.stroke(3);
            Draw.blend(Blending.additive);Draw.getShader().bind();Blending.additive.apply();
            float color=Draw.getColorPacked(),mix=Draw.getMixColorPacked(),z=Draw.z(),xs=Draw.xscl,ys=Draw.yscl,stroke=Lines.getStroke();
            Mat projection=new Mat(Draw.proj()),transform=new Mat(Draw.trans());var shader=Draw.getShader();
            IntBuffer before=Buffers.newIntBuffer(4),after=Buffers.newIntBuffer(4);
            Gl.colorMask(false,true,true,false);Core.gl.glGetIntegerv(0x0C23,before);
            parentClip=ScissorStack.push(new arc.math.geom.Rect(11,13,Core.graphics.getWidth()-23,Core.graphics.getHeight()-29));
            if(!parentClip)throw new AssertionError("No test parent clip");
            int[] graphics=glState();Object parent=ScissorStack.peek();float[] units=unitState();
            Unit u=samples.first();float ux=u.x,uy=u.y,rot=u.rotation,health=u.health,heat=u.mounts[0].heat;
            proto.variant=1;hudProjection.set(hud,proto);ResearchBatch.hudBackend=dual;hud.draw();Draw.flush();
            Core.gl.glGetIntegerv(0x0C23,after);
            for(int i=0;i<4;i++)if(before.get(i)!=after.get(i))throw new AssertionError("Color mask not restored");
            if(Draw.getColorPacked()!=color||Draw.getMixColorPacked()!=mix||Draw.z()!=z||Draw.xscl!=xs||Draw.yscl!=ys||Lines.getStroke()!=stroke||Draw.getBlend()!=Blending.additive||Draw.getShader()!=shader||!Arrays.equals(Draw.proj().val,projection.val)||!Arrays.equals(Draw.trans().val,transform.val))throw new AssertionError("Arc state not restored");
            if(u.x!=ux||u.y!=uy||u.rotation!=rot||u.health!=health||u.mounts[0].heat!=heat)throw new AssertionError("World mutated by render");
            if(!Arrays.equals(glState(),graphics)||ScissorStack.peek()!=parent)throw new AssertionError("GL program/FBO/viewport/parent scissor/blend state not restored");
            if(!Arrays.equals(unitState(),units))throw new AssertionError("Unit or weapon state mutated");
            if(Core.gl.glGetError()!=0)throw new AssertionError("Render GL error");
            out.child("state.txt").writeString("Scoped dual HUD: color/mix, shader/program, matrices, scale, Z, stroke, blending factors/equations/enabled, FBO, viewport, parent scissor and nondefault color mask restored. 55 fixture units and all mount state snapshots unchanged; GL error=0.\n");
        }finally{if(parentClip)ScissorStack.pop();Gl.colorMask(true,true,true,true);ResearchBatch.hudBackend=backend;hudProjection.set(hud,renderer);Core.batch=old;Draw.proj(oldP);Draw.trans(oldT);Draw.reset();Draw.blend();}
    }
    private int[] glState(){
        int[] tokens={0x8B8D,0x8CA6,0x0BA2,0x0C10,0x80C9,0x80C8,0x80CB,0x80CA,0x8009,0x883D};
        int[] result=new int[18];int next=0;IntBuffer values=Buffers.newIntBuffer(4);
        for(int token:tokens){values.clear();Core.gl.glGetIntegerv(token,values);for(int i=0;i<(token==0x0BA2||token==0x0C10?4:1);i++)result[next++]=values.get(i);}
        result[next++]=Core.gl.glIsEnabled(Gl.blend)?1:0;result[next]=Core.gl.glIsEnabled(Gl.scissorTest)?1:0;return result;
    }
    private float[] unitState(){
        arc.struct.FloatSeq values=new arc.struct.FloatSeq();for(Unit u:samples){values.add(u.x,u.y,u.rotation,u.health);values.add(u.vel.x,u.vel.y,u.elevation,u.shield);for(var m:u.mounts){values.add(m.heat,m.warmup,m.rotation,m.recoil);values.add(m.charge,m.reload,m.smoothReload);if(m.recoils!=null)values.addAll(m.recoils);}}return values.toArray();
    }
    private Pixmap image(UnitProjection r,Batch batch,Unit unit,float pixels,float alpha,Color background) {
        return image(r,batch,unit,pixels,alpha,background,true);
    }
    private Pixmap image(UnitProjection r,Batch batch,Unit unit,float pixels,float alpha,Color background,boolean advanced) {
        FrameBuffer f=qualityTarget; Batch old=Core.batch; Mat p=new Mat(Draw.proj()),t=new Mat(Draw.trans());
        Draw.flush();f.begin(background);
        try {configure(batch);Draw.proj(384,0,512,512);r.clear();packet(r,unit,1,advanced,pixels,alpha);return ScreenUtils.getFrameBufferPixmap(0,0,512,512);}
        finally {Draw.flush();f.end();Core.batch=old;Draw.proj(p);Draw.trans(t);}
    }
    private void quality() {
        float saved=Time.time;Time.time=3.1415927f;
        try {
            for(int type=0;type<5;type++)for(int state=0;state<8;state++)for(float alpha:new float[]{1,.32f}) {
                Unit unit=samples.get(type*11);pose(unit,state);
                for(Color bg:new Color[]{Color.clear,new Color(.4f,.4f,.4f,.3f),Color.white}) {
                    Pixmap reference=image(stock,client,unit,100,alpha,bg);
                    try {
                        for(int candidate=0;candidate<7;candidate++) {
                            Batch b=switch(candidate){case 1->vbo;case 2->pma;case 3->channels;case 4->dual;case 6->fetch;default->client;};if(b==null)continue;
                            String name=switch(candidate){case 0->"skip";case 1->"skip+vbo";case 2->"PMA-RGB-only";case 3->"channels";case 4->"dual";case 6->"fetch";default->"skip+java+share";};
                            proto.variant=candidate==5?7:1;Pixmap actual=image(proto,b,unit,100,alpha,bg);
                            try {diff("projection",unit.type.name,state,name,bg.toString(),alpha,reference,actual);}
                            finally {actual.dispose();}
                        }
                    } finally {reference.dispose();}
                }
            }
        } finally {Time.time=saved;}
        System.out.println("Research pixel parity matrix complete");save();
    }
    private void diff(String scope,String type,int state,String name,String bg,float alpha,Pixmap a,Pixmap b) {
        long changed=0,total=0;int max=0,maxA=0;
        for(int y=0;y<a.height;y++)for(int x=0;x<a.width;x++){
            int p=a.get(x,y),q=b.get(x,y);if(p!=q)changed++;
            maxA=Math.max(maxA,Math.abs((p&255)-(q&255)));
            for(int s=8;s<=24;s+=8){int d=Math.abs((p>>>s&255)-(q>>>s&255));max=Math.max(max,d);total+=d;}
        }
        parity.append(String.format(Locale.ROOT,"%s,%s,%d,%s,%s,%.2f,%d,%d,%d,%.6f%n",scope,type,state,name,bg,alpha,changed,max,maxA,total/(3d*a.width*a.height)));
    }
    private void hudExperiments() throws Exception {
        int width=Core.graphics.getWidth(),height=Core.graphics.getHeight();
        FrameBuffer f=new FrameBuffer(width,height);
        Batch old=Core.batch;Mat oldP=new Mat(Draw.proj()),oldT=new Mat(Draw.trans());float oldTime=Time.time;
        Time.time=3.1415927f;
        if(Core.scene.getWidth()!=width||Core.scene.getHeight()!=height)throw new AssertionError("Scene/window mismatch");
        try {
            for(int scene=0;scene<48;scene++) {
                int direction=scene%4,groupCount=scene/12%2==0?1:10;
                float ui=new float[]{.75f,1,2}[scene/4%3];
                for(int g=0;g<10;g++){Vars.control.input.controlGroups[g]=new IntSeq();if(g<groupCount)Vars.control.input.controlGroups[g].add(samples.get(g).id);}
                hud.model.reconcile(Vars.control.input.controlGroups,id->mindustry.gen.Groups.unit.getByID(id),samples,Team.sharded,true);
                hud.model.sample(Team.sharded,hud.settings,++frameEpoch);
                hud.settings.vertical=direction<2;hud.settings.reverse=direction%2!=0;
                hud.settings.internalProjection=scene/24==0;hud.settings.advanced=true;hud.settings.projection=true;
                hud.settings.length=Math.max(160,Math.min(width,height)*1.2f);hud.settings.breadth=Math.max(80,Math.min(width,height)*.65f);
                hud.settings.occupancy=scene%9==0?0:scene%9==1?3:1;
                hud.settings.spaceMode=ModSettings.SpaceMode.values()[scene%3];
                hud.settings.freeSpaceMode=ModSettings.FreeSpaceMode.values()[scene/3%3];
                hud.settings.collapse=true;hud.editing=scene%2!=0;
                arc.scene.ui.layout.Scl.setProduct(ui);
                hud.layout.rebuild(hud.settings,width,height,ui,false);
                hud.setBounds(.37f,.43f,hud.layout.width,hud.layout.height);
                for(var b:hud.layout.boxes){b.expansion=scene%5==0?.4f:1;hud.layout.animate(b,hud.settings,false,false,0);}
                ResearchBatch.dropEmptyClipFlush=false;ResearchBatch.guardedClip=false;ResearchBatch.hudBackend=null;
                hudProjection.set(hud,stock);
                Pixmap reference=hudImage(f,client,width,height);
                try {
                    for(int candidate=0;candidate<4;candidate++) {
                        proto.variant=1;hudProjection.set(hud,proto);
                        ResearchBatch.dropEmptyClipFlush=candidate>=1;ResearchBatch.guardedClip=candidate>=2;
                        ResearchBatch.hudBackend=candidate==3?dual:null;
                        if(candidate==3&&dual==null)continue;
                        Pixmap actual=hudImage(f,client,width,height);
                        try{diff("HUD-"+width+"x"+height,"scene-"+scene,scene,candidate==0?"skip":candidate==1?"skip+empty-clip":candidate==2?"skip+safe-clip":"skip+safe-clip+dual","gray",hud.editing?.32f:1,reference,actual);}
                        finally{actual.dispose();}
                    }
                } finally{reference.dispose();}
                if(scene==0||scene==23||scene==47) {
                    for(int candidate=0;candidate<4;candidate++) {
                        proto.variant=1;hudProjection.set(hud,candidate==0?stock:proto);
                        ResearchBatch.dropEmptyClipFlush=candidate>=1;ResearchBatch.guardedClip=candidate>=2;
                        ResearchBatch.hudBackend=candidate==3?dual:null;
                        if(candidate==3&&dual==null)continue;
                        f.begin(Color.clear);Core.batch=client;Draw.reset();Draw.proj(0,0,width,height);Draw.trans(identity);Draw.flush();
                        client.resetCounts();client.count=true;
                        try(var gl=new ResearchBatch.GLCounts()) {
                            hud.draw();Draw.flush();
                            counts.append(String.format(Locale.ROOT,"HUD-%dx%d,scene-%d,%d,%d,true,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",width,height,scene,scene,groupCount,candidate,
                                client.quads,client.zeroAlpha,client.flushes,client.nonempty,client.blends,client.textures,gl.draws,gl.uploads,gl.uploadBytes,gl.textureBinds,gl.blendFunctions,gl.scissors,gl.shaders,gl.attribPointers));
                        }finally{client.count=false;f.end();}
                        hudTimed(f,width,height,scene,"candidate-"+candidate);
                    }
                }
            }
            out.child("hud-"+width+".txt").writeString("48 configurations; 4 orientations, UI .75/1/2, 1/10 saved + unassigned, internal/external, mixed distribution, fractional position, editor, partial collapse, 0/100/300% occupancy. Safe clips elided cumulative="+ResearchBatch.clipsElided+"\n");
        } finally {
            ResearchBatch.hudBackend=null;ResearchBatch.guardedClip=ResearchBatch.dropEmptyClipFlush=false;hudProjection.set(hud,originalHudRenderer);
            Draw.flush();Core.batch=old;Draw.proj(oldP);Draw.trans(oldT);Time.time=oldTime;arc.scene.ui.layout.Scl.setProduct(1);f.dispose();
        }
        save();System.out.println("Research HUD "+width+"x"+height+" complete");
    }
    private Pixmap hudImage(FrameBuffer f,Batch b,int width,int height) {
        Core.batch=b;Draw.flush();f.begin(new Color(.12f,.12f,.12f,.4f));
        try{Draw.reset();Draw.scl(1);Draw.blend();Draw.proj(0,0,width,height);Draw.trans(identity);hud.draw();Draw.flush();return ScreenUtils.getFrameBufferPixmap(0,0,width,height);}
        finally{Draw.flush();f.end();}
    }
    private void hudTimed(FrameBuffer f,int w,int h,int scene,String name) {
        Core.batch=client;f.begin(Color.clear);Draw.proj(0,0,w,h);Draw.trans(identity);
        try {
            for(int i=0;i<100;i++){hud.draw();Draw.flush();}Core.gl.glFinish();
            long[] e=new long[200];long before=allocations.getThreadAllocatedBytes(thread),total=System.nanoTime();
            for(int i=0;i<e.length;i++){long start=System.nanoTime();hud.draw();Draw.flush();e[i]=System.nanoTime()-start;}
            Core.gl.glFinish();double complete=(System.nanoTime()-total)/(1000d*e.length),bytes=(allocations.getThreadAllocatedBytes(thread)-before)/(double)e.length;Arrays.sort(e);
            times.append(String.format(Locale.ROOT,"HUD-%dx%d,scene-%d,%d,11,true,0,%s,0,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",w,h,scene,scene,name,e[100]/1000d,e[190]/1000d,e[198]/1000d,e[0]/1000d,e[199]/1000d,complete,bytes));
        }finally{Draw.flush();f.end();}
    }
    private void setupFrameScene(int count) throws Exception {
        Vars.state.teams.updateTeamStats();
        int have=Team.sharded.data().units.size;
        for(int i=have;i<count;i++)UnitTypes.dagger.spawn(Team.sharded,80+i%50*12,80+i/50*12);
        Vars.state.teams.updateTeamStats();
        if(Team.sharded.data().units.size!=count)throw new AssertionError("Incorrect scene unit count");
        for(int g=0;g<10;g++){Vars.control.input.controlGroups[g]=new IntSeq();for(int i=g;i<Team.sharded.data().units.size-10;i+=10)Vars.control.input.controlGroups[g].add(Team.sharded.data().units.get(i).id);}
        for(Unit u:samples)pose(u,2);
        hud.settings.vertical=true;hud.settings.reverse=false;hud.settings.spaceMode=ModSettings.SpaceMode.PROPORTIONAL;hud.settings.freeSpaceMode=ModSettings.FreeSpaceMode.LIMITED;
        hud.settings.length=640;hud.settings.breadth=240;hud.settings.xFraction=0;hud.settings.yFraction=.5f;hud.settings.collapse=false;hud.settings.internalProjection=false;hud.settings.occupancy=1;hud.editing=false;
        Vars.state.set(mindustry.core.GameState.State.paused);Vars.control.input.commandMode=true;hud.settings.showBuild=true;hud.changed();
        frameBlock=0;frameSample=0;warm=0;setFrameMode(0);
    }
    private void setFrameMode(int mode) throws Exception {
        ResearchBatch.hudBackend=null;ResearchBatch.guardedClip=false;ResearchBatch.dropEmptyClipFlush=false;
        Vars.control.input.commandMode=true;hud.settings.showBuild=true;hud.settings.advanced=mode>=2;ResearchBatch.suppressHud=mode==0;
        hudProjection.set(hud,mode<=2?originalHudRenderer:proto);proto.variant=1;
        if(mode==4)ResearchBatch.hudBackend=dual;
        ResearchBatch.measureHud=true;hud.changed();ResearchBatch.hudNanos=0;
    }
    private void frameTests(long elapsed) throws Exception {
        if(++warm<=50)return;
        if(frameModes[frameBlock]!=0&&hud.layout.activeCount<10)throw new AssertionError("Empty HUD invalidates frame sample");
        if(frameSample==0){
            HashSet<Integer> unique=new HashSet<>();int conquerCount=0;float min=Float.POSITIVE_INFINITY,max=0;
            for(var b:hud.layout.boxes)if(b.visible&&b.group.representative!=null){Unit u=b.group.representative.unit;unique.add(u.id);if(u.type==UnitTypes.conquer)conquerCount++;float size=hud.layout.projectionPixels(b,u.type.hitSize,hud.settings);min=Math.min(min,size);max=Math.max(max,size);}
            frameFixture.append(String.format(Locale.ROOT,"%d,%d,%d,%d,%d,%d,%.3f,%.3f,%s,%s%n",sceneIndex==0?500:1000,frameBlock,frameModes[frameBlock],hud.layout.activeCount,unique.size(),conquerCount,min,max,Vars.control.input.commandMode,ResearchBatch.suppressHud));
        }
        gameSamples[frameSample]=elapsed;hudSamples[frameSample]=ResearchBatch.hudNanos;frameSample++;
        if(frameSample<gameSamples.length)return;
        int over8=0,over16=0;for(long x:gameSamples){if(x>8_333_333)over8++;if(x>16_666_667)over16++;}
        Arrays.sort(gameSamples);Arrays.sort(hudSamples);
        frames.append(String.format(Locale.ROOT,"%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d%n",sceneIndex==0?500:1000,frameBlock,frameModes[frameBlock],gameSamples[60]/1000d,gameSamples[114]/1000d,gameSamples[118]/1000d,gameSamples[0]/1000d,gameSamples[119]/1000d,hudSamples[60]/1000d,hudSamples[114]/1000d,over8,over16));
        frameBlock++;frameSample=0;warm=0;
        if(frameBlock>=frameModes.length){if(sceneIndex==0){sceneIndex++;setupFrameScene(1000);}else{ResearchBatch.measureHud=false;ResearchBatch.hudBackend=null;hudProjection.set(hud,originalHudRenderer);stage=7;}}
        else setFrameMode(frameModes[frameBlock]);
        save();
    }
    private void save() { out.child("times.csv").writeString(times.toString());out.child("counts.csv").writeString(counts.toString());out.child("parity.csv").writeString(parity.toString());out.child("frames.csv").writeString(frames.toString());out.child("frame-fixture.csv").writeString(frameFixture.toString()); }
    private void dispose(){target.dispose();qualityTarget.dispose();client.dispose();vbo.dispose();noGL.dispose();if(pma!=null)pma.dispose();if(channels!=null)channels.dispose();if(dual!=null)dual.dispose();if(fetch!=null)fetch.dispose();for(Unit u:samples)u.remove();}
}
