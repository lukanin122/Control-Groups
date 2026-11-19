package controlgroups;

import arc.Core;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.util.Buffers;
import java.nio.IntBuffer;
import java.lang.reflect.*;

/** Diagnostics only. Never included in the production JAR or active during timed blocks. */
public final class StageABatch {
    public static boolean baselineClips=true, measureHud, suppressHud;
    public static boolean keepLeading=true;
    public static long hudNanos;
    public static class Counting extends SpriteBatch {
        public boolean count;
        public long flushes,nonempty,quads,zeroAlpha,blendChanges;
        public Counting(){super(4096);}
        public void resetCounts(){flushes=nonempty=quads=zeroAlpha=blendChanges=0;}
        @Override protected void flush(){if(count){flushes++;if(idx>0)nonempty++;}super.flush();}
        @Override protected void setBlending(Blending b){if(count&&b!=blending)blendChanges++;super.setBlending(b);}
        @Override protected void draw(TextureRegion r,float x,float y,float ox,float oy,float w,float h,float rotation){
            if(count){quads++;if((Float.floatToRawIntBits(colorPacked)>>>24)==0)zeroAlpha++;}super.draw(r,x,y,ox,oy,w,h,rotation);
        }
        @Override protected void draw(Texture t,float[] v,int offset,int n){if(count)quads+=n/24;super.draw(t,v,offset,n);}
    }
    public static final class Null extends SpriteBatch {
        public Null(){super(0);}
        @Override protected void draw(TextureRegion r,float x,float y,float ox,float oy,float w,float h,float rotation){}
        @Override protected void draw(Texture t,float[] v,int offset,int n){}
        @Override protected void flush(){}
    }
    public static final class GLCounts implements AutoCloseable {
        private final GL20 original=Core.gl,original20=Core.gl20;
        private final IntBuffer state=Buffers.newIntBuffer(4);
        public final StringBuilder drawStates=new StringBuilder();
        public long draws,blendFunctions,textures,scissors,uploads;
        public GLCounts(){this(false);}
        public GLCounts(boolean captureDrawState){
            GL20 proxy=(GL20)Proxy.newProxyInstance(GL20.class.getClassLoader(),new Class<?>[]{GL20.class},(o,m,a)->{
                switch(m.getName()){
                    case "glDrawElements","glDrawArrays"->draws++;
                    case "glBlendFuncSeparate"->blendFunctions++;
                    case "glBindTexture"->textures++;
                    case "glScissor"->scissors++;
                    case "glBufferData","glBufferSubData"->uploads++;
                }
                if(captureDrawState&&(m.getName().equals("glDrawElements")||m.getName().equals("glDrawArrays"))){
                    drawStates.append(a[1]).append(':').append(original.glIsEnabled(Gl.scissorTest)).append(':');
                    for(int token:new int[]{0x0C10,0x80C9,0x80C8,0x80CB,0x80CA,0x8009,0x883D,0x8B8D,0x8069}){
                        state.clear();original.glGetIntegerv(token,state);for(int i=0;i<(token==0x0C10?4:1);i++)drawStates.append(state.get(i)).append('/');
                    }drawStates.append(';');
                }
                try{return m.invoke(original,a);}catch(InvocationTargetException e){throw e.getCause();}
            });Core.gl=Core.gl20=proxy;
        }
        @Override public void close(){Core.gl=original;Core.gl20=original20;}
    }
}
