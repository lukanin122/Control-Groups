package controlgroups;

import arc.Core;
import arc.graphics.Blending;
import arc.graphics.Texture;
import arc.graphics.g2d.*;
import mindustry.gen.Unit;
import java.util.Arrays;

/** EXPERIMENT ONLY: capture/replay, preserving each group's ordered stream. No general sorting. */
public final class ResearchQueue extends SpriteBatch {
    private final float[] vertices=new float[4096*24];
    private final Texture[] textures=new Texture[4096];
    private final Blending[] blends=new Blending[4096];
    private final int[] starts=new int[11],ends=new int[11],cursor=new int[11];
    private final float[] minX=new float[11],minY=new float[11],maxX=new float[11],maxY=new float[11];
    private int size,group,groups;
    public boolean disjoint;
    public ResearchQueue(){super(0);}
    public void capture(UnitProjection renderer,Unit unit,int count,float pixels,boolean advanced) {
        Batch old=Core.batch;Draw.flush();Core.batch=this;size=0;groups=count;
        Arrays.fill(minX,Float.POSITIVE_INFINITY);Arrays.fill(minY,Float.POSITIVE_INFINITY);
        Arrays.fill(maxX,Float.NEGATIVE_INFINITY);Arrays.fill(maxY,Float.NEGATIVE_INFINITY);
        try {
            if(renderer instanceof ResearchProjection p)p.beginFrame();
            for(group=0;group<count;group++){
                starts[group]=size;
                renderer.draw(unit,group,(group+.5f)*1280/count,256,pixels,180,unit.type.hitSize,0,advanced,1);
                ends[group]=size;
            }
        }finally{Core.batch=old;}
        disjoint=true;
        for(int a=0;a<count;a++)for(int b=0;b<a;b++)
            if(minX[a]-.5f<maxX[b]+.5f&&maxX[a]+.5f>minX[b]-.5f&&minY[a]-.5f<maxY[b]+.5f&&maxY[a]+.5f>minY[b]-.5f)disjoint=false;
    }
    @Override protected void draw(TextureRegion r,float x,float y,float ox,float oy,float w,float h,float rotation){
        check();constructVertices(vertices,size*24,r,x,y,ox,oy,w,h,rotation);record(r.texture);
    }
    @Override protected void draw(Texture t,float[] data,int start,int count){for(int i=start;i<start+count;i+=24){check();System.arraycopy(data,i,vertices,size*24,24);record(t);}}
    private void check(){if(size>=textures.length)throw new IllegalStateException("Queue overflow: direct fallback required");}
    private void record(Texture texture){
        textures[size]=texture;blends[size]=blending;
        for(int i=size*24;i<size*24+24;i+=6){minX[group]=Math.min(minX[group],vertices[i]);maxX[group]=Math.max(maxX[group],vertices[i]);minY[group]=Math.min(minY[group],vertices[i+1]);maxY[group]=Math.max(maxY[group],vertices[i+1]);}
        size++;
    }
    @Override protected void flush(){}
    public void replay(boolean phase,boolean requireDisjoint) {
        if(!phase||requireDisjoint&&!disjoint){for(int i=0;i<size;i++)emit(i);}
        else {
            System.arraycopy(starts,0,cursor,0,groups);
            while(true){
                int first=-1;for(int g=0;g<groups;g++)if(cursor[g]<ends[g]){first=cursor[g];break;}
                if(first<0)break;
                Blending blend=blends[first];Texture texture=textures[first];
                for(int g=0;g<groups;g++)while(cursor[g]<ends[g]&&blends[cursor[g]]==blend&&textures[cursor[g]]==texture)emit(cursor[g]++);
            }
        }
        if(Draw.getBlend()!=Blending.normal){Draw.flush();Draw.blend();}
        Draw.flush();
    }
    private void emit(int i){if(Draw.getBlend()!=blends[i]){if(!(Core.batch instanceof ResearchBatch.Unified))Draw.flush();Draw.blend(blends[i]);}Draw.vert(textures[i],vertices,i*24,24);}
}
