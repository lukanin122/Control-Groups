package controlgroups;

import arc.Core;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.Shader;
import arc.math.Mat;
import arc.util.Buffers;
import java.lang.reflect.*;
import java.nio.IntBuffer;
import java.util.Arrays;

/** EXPERIMENT ONLY. Generated mod snapshot owns these objects; stock mod is unchanged. */
public final class ResearchBatch {
    public static boolean dropEmptyClipFlush, guardedClip;
    public static long clipsElided;
    public static Batch hudBackend;
    public static boolean measureHud;
    public static boolean suppressHud;
    public static long hudNanos;
    private static final Mat hudProjection = new Mat(), hudTransform = new Mat();
    private static float hudColor, hudMix, hudZ, hudXs, hudYs, hudStroke;
    private static Blending hudBlend;
    private static Shader hudShader;

    public static Batch beginHud() {
        if (hudBackend == null) return null;
        Batch old = Core.batch; Draw.flush();
        hudProjection.set(Draw.proj());hudTransform.set(Draw.trans());
        hudColor=Draw.getColorPacked();hudMix=Draw.getMixColorPacked();hudZ=Draw.z();hudXs=Draw.xscl;hudYs=Draw.yscl;hudStroke=Lines.getStroke();
        hudBlend=Draw.getBlend();hudShader=Draw.getShader();
        Core.batch=hudBackend;Draw.proj(hudProjection);Draw.trans(hudTransform);Draw.color(hudColor);Draw.mixcol(hudMix);Draw.scl(hudXs,hudYs);Draw.z(hudZ);Draw.blend(hudBlend);
        if(hudBackend instanceof Unified u)u.beginScope();
        return old;
    }
    public static void endHud(Batch old) {
        if(old==null)return;
        Draw.flush();Core.batch=old;hudShader.bind();hudBlend.apply();
        Draw.color(hudColor);Draw.mixcol(hudMix);Draw.scl(hudXs,hudYs);Draw.z(hudZ);Lines.stroke(hudStroke);
    }

    public static boolean skipClip(GroupsHud hud, HudLayout.Box box) {
        if (!guardedClip || box.expansion < .999999f) return false;
        // Conservative identity-Scene raster margin; tested here, not a universal transform proof.
        float margin = 3 * Core.scene.getWidth() / Core.graphics.getWidth();
        for (int i = 0; i < box.cellCount; i++) {
            var r = box.cells[i].rect; var c = box.content;
            if (r.x < c.x + margin || r.y < c.y + margin || r.x + r.width > c.x + c.width - margin ||
                r.y + r.height > c.y + c.height - margin) return false;
        }
        clipsElided++; return true;
    }

    public static class Counting extends SpriteBatch {
        public boolean count;
        public long flushes, nonempty, quads, zeroAlpha, blends, textures;
        public long normal, additive, visibleNormal, visibleAdditive;
        private final int[] runSizes = new int[2048], runModes = new int[2048];
        private int runCount;
        public Counting(boolean vbo) {
            super(4096);
            if (vbo) {
                Mesh old = mesh;
                mesh = new Mesh(false, false, 4096 * 4, 4096 * 6,
                    VertexAttribute.position, VertexAttribute.color, VertexAttribute.texCoords, VertexAttribute.mixColor);
                initIndices(mesh, 4096); buffer = mesh.getVerticesBuffer(); old.dispose();
            }
        }
        public void resetCounts() { flushes = nonempty = quads = zeroAlpha = blends = textures = normal = additive = visibleNormal = visibleAdditive = 0; runCount=0; }
        public String runs(){StringBuilder s=new StringBuilder();for(int i=0;i<runCount;i++){if(i!=0)s.append(';');s.append(runModes[i]==1?'A':runModes[i]==0?'N':'X').append(runSizes[i]);}return s.toString();}
        @Override protected void flush() { if (count) { flushes++; if (idx > 0) {nonempty++;if(runCount<runSizes.length){runSizes[runCount]=idx/24;runModes[runCount++]=blending==Blending.normal?0:blending==Blending.additive?1:2;}} } super.flush(); }
        @Override protected void setBlending(Blending b) { if (count && b != blending) blends++; super.setBlending(b); }
        @Override protected void draw(TextureRegion r, float x, float y, float ox, float oy, float w, float h, float a) {
            if (count) { quads++; if ((Float.floatToRawIntBits(colorPacked) >>> 24) == 0) zeroAlpha++; if (r.texture != lastTexture) textures++; }
            if(count){boolean visible=(Float.floatToRawIntBits(colorPacked)>>>24)!=0;if(blending==Blending.normal){normal++;if(visible)visibleNormal++;}else if(blending==Blending.additive){additive++;if(visible)visibleAdditive++;}}
            super.draw(r, x, y, ox, oy, w, h, a);
        }
        @Override protected void draw(Texture t, float[] v, int start, int n) {
            if (count) { quads += n / 24; if (t != lastTexture) textures++; }
            super.draw(t, v, start, n);
        }
    }

    public static final class Null extends SpriteBatch {
        public long flushes, nonempty, quads, zeroAlpha, blends, textures;
        private int pending;
        private Texture texture;
        public Null() { super(0); }
        public void reset() { flushes = nonempty = quads = zeroAlpha = blends = textures = 0; pending = 0; texture = null; }
        @Override protected void setBlending(Blending b) { if (b != blending) blends++; blending = b; }
        @Override protected void draw(TextureRegion r, float x, float y, float ox, float oy, float w, float h, float a) {
            if (r.texture != texture) { textures++; flush(); texture = r.texture; }
            quads++; pending++; if ((Float.floatToRawIntBits(colorPacked) >>> 24) == 0) zeroAlpha++;
        }
        @Override protected void draw(Texture t, float[] v, int start, int n) {
            if (t != texture) { textures++; flush(); texture = t; } quads += n / 24; pending += n / 24;
        }
        @Override protected void flush() { flushes++; if (pending > 0) nonempty++; pending = 0; }
    }

    private static void initIndices(Mesh mesh, int size) {
        short[] ix = new short[size * 6]; short vertex = 0;
        for (int i = 0; i < ix.length; i += 6, vertex += 4) {
            ix[i] = vertex; ix[i+1]=(short)(vertex+1); ix[i+2]=(short)(vertex+2);
            ix[i+3]=(short)(vertex+2); ix[i+4]=(short)(vertex+3); ix[i+5]=vertex;
        }
        mesh.setIndices(ix); mesh.getVerticesBuffer().position(0); mesh.getVerticesBuffer().limit(mesh.getVerticesBuffer().capacity());
    }

    /** 0: RGB-only PMA experiment, 1: two-channel replay, 2: dual-source, 3: coherent fetch. */
    public static final class Unified extends SpriteBatch {
        public final int mode;
        private final Blending fixed;
        private Blending logical = Blending.normal;
        private final float[] quad = new float[24], vertex = new float[7];
        private final IntBuffer mask = Buffers.newIntBuffer(4);
        private final boolean[] savedMask = {true,true,true,true};
        public long submissions, vertices;

        public Unified(int mode, boolean vbo) {
            super(0); this.mode = mode;
            mesh = new Mesh(!vbo, false, 4096*4, 4096*6, VertexAttribute.position, VertexAttribute.color,
                VertexAttribute.texCoords, VertexAttribute.mixColor, new VertexAttribute(1,"a_mode"));
            initIndices(mesh,4096); buffer = mesh.getVerticesBuffer();
            shader = mode == 2 ? new DesktopShader(VERTEX_330, FRAGMENT_330) : mode == 3 ?
                new DesktopShader(VERTEX_330,FRAGMENT_FETCH) : new Shader(VERTEX,FRAGMENT);
            ownsShader = true;
            fixed = mode == 3 ? Blending.disabled : mode == 2 ? new Blending(Gl.srcAlpha,0x88FB,Gl.one,Gl.oneMinusSrcAlpha) :
                new Blending(Gl.one,Gl.oneMinusSrcAlpha,Gl.one,Gl.oneMinusSrcAlpha);
        }
        public void beginScope() {
            mask.clear(); Core.gl.glGetIntegerv(0x0C23,mask);
            for (int i=0;i<4;i++) savedMask[i]=mask.get(i)!=0;
        }
        @Override protected Blending getBlending() { return logical; }
        @Override protected void setBlending(Blending b) {
            if (b != Blending.normal && b != Blending.additive) throw new UnsupportedOperationException("Custom blend requires fallback");
            logical = b;
        }
        @Override protected void draw(TextureRegion r,float x,float y,float ox,float oy,float w,float h,float a) {
            constructVertices(quad,0,r,x,y,ox,oy,w,h,a); append(r.texture,quad,0,24);
        }
        @Override protected void draw(Texture t,float[] v,int start,int n) { append(t,v,start,n); }
        private void append(Texture t,float[] v,int start,int n) {
            if (t != lastTexture) { flush(); lastTexture = t; }
            for (int i=start;i<start+n;i+=6) {
                if (idx + 7 > buffer.capacity()) flush();
                System.arraycopy(v,i,vertex,0,6); vertex[6] = logical == Blending.additive ? 1 : 0;
                buffer.put(vertex); idx+=7; vertices++;
            }
        }
        @Override protected void flush() {
            if (idx == 0) return;
            Shader s = getShader(); s.bind(); setupMatrices(); s.setUniformi("u_texture",0);
            int floats = idx;
            mesh.getVerticesBuffer(); buffer.position(0); buffer.limit(floats);
            lastTexture.bind(); fixed.apply(); Gl.depthMask(false);
            if (mode >= 2) { mesh.render(s,Gl.triangles,0,floats/28*6); submissions++; }
            else {
                s.setUniformf("u_alphaPass",0);
                Gl.colorMask(savedMask[0],savedMask[1],savedMask[2],mode==0 && savedMask[3]);
                mesh.render(s,Gl.triangles,0,floats/28*6); submissions++;
                if (mode == 1 && savedMask[3]) {
                    s.setUniformf("u_alphaPass",1); Gl.colorMask(false,false,false,true);
                    mesh.render(s,Gl.triangles,0,floats/28*6); submissions++;
                }
                Gl.colorMask(savedMask[0],savedMask[1],savedMask[2],savedMask[3]);
            }
            buffer.limit(buffer.capacity()); buffer.position(0); idx=0;
        }
    }

    private static final String VERTEX = "attribute vec4 a_position;attribute vec4 a_color;attribute vec2 a_texCoord0;attribute vec4 a_mix_color;attribute float a_mode;uniform mat4 u_projTrans;varying vec4 v_color;varying vec4 v_mix_color;varying vec2 v_texCoords;varying float v_mode;void main(){v_color=a_color;v_color.a*=255.0/254.0;v_mix_color=a_mix_color;v_mix_color.a*=255.0/254.0;v_texCoords=a_texCoord0;v_mode=a_mode;gl_Position=u_projTrans*a_position;}";
    private static final String FRAGMENT = "varying lowp vec4 v_color;varying lowp vec4 v_mix_color;varying highp vec2 v_texCoords;varying float v_mode;uniform sampler2D u_texture;uniform float u_alphaPass;void main(){vec4 t=texture2D(u_texture,v_texCoords);vec4 c=v_color*mix(t,vec4(v_mix_color.rgb,t.a),v_mix_color.a);gl_FragColor=u_alphaPass>0.5?vec4(0.0,0.0,0.0,c.a):vec4(c.rgb*c.a,c.a*(1.0-v_mode));}";
    private static final String VERTEX_330 = VERTEX.replace("attribute","in").replace("varying","out");
    private static final String FRAGMENT_330 = "in vec4 v_color;in vec4 v_mix_color;in vec2 v_texCoords;in float v_mode;uniform sampler2D u_texture;layout(location=0,index=0) out vec4 primary;layout(location=0,index=1) out vec4 secondary;void main(){vec4 t=texture(u_texture,v_texCoords);primary=v_color*mix(t,vec4(v_mix_color.rgb,t.a),v_mix_color.a);secondary=vec4(0.0,0.0,0.0,(1.0-v_mode)*primary.a);}";
    private static final String FRAGMENT_FETCH = "#extension GL_EXT_shader_framebuffer_fetch : require\nin vec4 v_color;in vec4 v_mix_color;in vec2 v_texCoords;in float v_mode;uniform sampler2D u_texture;layout(location=0) inout vec4 primary;void main(){vec4 t=texture(u_texture,v_texCoords);vec4 s=v_color*mix(t,vec4(v_mix_color.rgb,t.a),v_mix_color.a);vec4 d=primary;primary=vec4(s.rgb*s.a+d.rgb*(1.0-(1.0-v_mode)*s.a),s.a+d.a*(1.0-s.a));}";
    private static final class DesktopShader extends Shader {
        DesktopShader(String v,String f) { super(v,f); }
        @Override protected String preprocess(String source,boolean fragment) { return "#version 330\n"+source; }
    }

    /** Count-only GL proxy: never enabled during timing. */
    public static final class GLCounts implements AutoCloseable {
        private final GL20 original = Core.gl, original20 = Core.gl20;
        public long draws, uploads, uploadBytes, textureBinds, blendFunctions, scissors, shaders, attribPointers;
        public GLCounts() {
            GL20 proxy = (GL20)Proxy.newProxyInstance(GL20.class.getClassLoader(),new Class<?>[]{GL20.class},(o,m,a)->{
                switch(m.getName()) {
                    case "glDrawElements", "glDrawArrays" -> draws++;
                    case "glBufferData" -> { uploads++; uploadBytes += (Integer)a[1]; }
                    case "glBufferSubData" -> { uploads++; uploadBytes += (Integer)a[2]; }
                    case "glBindTexture" -> textureBinds++;
                    case "glBlendFuncSeparate" -> blendFunctions++;
                    case "glScissor" -> scissors++;
                    case "glUseProgram" -> shaders++;
                    case "glVertexAttribPointer" -> attribPointers++;
                }
                try { return m.invoke(original,a); } catch(InvocationTargetException e) { throw e.getCause(); }
            });
            Core.gl=Core.gl20=proxy;
        }
        @Override public void close() { Core.gl=original; Core.gl20=original20; }
    }
}
