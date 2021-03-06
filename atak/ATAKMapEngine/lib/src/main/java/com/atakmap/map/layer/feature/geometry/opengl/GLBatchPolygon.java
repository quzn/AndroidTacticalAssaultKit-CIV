
package com.atakmap.map.layer.feature.geometry.opengl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;

import android.graphics.Color;
import android.opengl.GLES30;

import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.lang.Unsafe;
import com.atakmap.map.MapRenderer;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.geometry.GeometryCollection;
import com.atakmap.map.layer.feature.geometry.GeometryFactory;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.style.Style;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.Polygon;
import com.atakmap.map.layer.feature.style.BasicFillStyle;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.opengl.GLMapSurface;
import com.atakmap.map.opengl.GLMapView;
import com.atakmap.math.MathUtils;
import com.atakmap.math.Matrix;
import com.atakmap.math.PointD;
import com.atakmap.opengl.GLES20FixedPipeline;
import com.atakmap.opengl.GLRenderBatch2;
import com.atakmap.opengl.Tessellate;

public class GLBatchPolygon extends GLBatchLineString {

    private final static String TAG = "GLBatchPolygon";

    float fillColorR;
    float fillColorG;
    float fillColorB;
    float fillColorA;
    int fillColor;
    protected boolean hasBeenExtruded;

    boolean drawStroke;

    DoubleBuffer polyTriangles;
    FloatBuffer polyVertices;

    protected DoubleBuffer extrudedPolygons;
    protected FloatBuffer projectedExtrudedPolygons;

    protected DoubleBuffer extrudedOutline;
    protected FloatBuffer projectedExtrudedOutline;

    public GLBatchPolygon(GLMapSurface surface) {
        this(surface.getGLMapView());
    }

    public GLBatchPolygon(MapRenderer surface) {
        super(surface, 2);

        this.fillColorR = 0.0f;
        this.fillColorG = 0.0f;
        this.fillColorB = 0.0f;
        this.fillColorA = 0.0f;
        this.fillColor = 0;
        this.hasBeenExtruded = false;
        extrudedPolygons = null;
        projectedExtrudedPolygons = null;
    }

    @Override
    public void setStyle(Style style) {
        super.setStyle(style);

        if(style instanceof CompositeStyle)
            style = CompositeStyle.find((CompositeStyle)style, BasicFillStyle.class);

        boolean hasFill = style instanceof BasicFillStyle;
        if (hasFill) {
            BasicFillStyle basicFill = (BasicFillStyle)style;

            this.fillColor = basicFill.getColor();

            this.fillColorR = Color.red(this.fillColor) / 255f;
            this.fillColorG = Color.green(this.fillColor) / 255f;
            this.fillColorB = Color.blue(this.fillColor) / 255f;
            this.fillColorA = Color.alpha(this.fillColor) / 255f;
        } else {
            this.fillColorA = 0.0f;
        }

        // validate the geometry to update polygon tessellation for fill
        this.validateGeometry();

        this.drawStroke = !hasFill;
        final RenderState[] rs = this.renderStates;
        if(!this.drawStroke && rs != null) {
            for(RenderState s : rs) {
                this.drawStroke |= (s.strokeColorA != 0f);
                if(this.drawStroke)
                    break;
            }
        }
    }

    @Override
    protected void setGeometryImpl(final ByteBuffer blob, final int type) {
        hasBeenExtruded = false;
        final int numRings = blob.getInt();
        if (numRings == 0) {
            this.numRenderPoints = 0;
        } else {
            super.setGeometryImpl(blob, type);
        }
    }

    @Override
    protected boolean validateGeometry() {
        final boolean updated = super.validateGeometry();

        // if the polygon has a fill and has at least 3 points then we need to
        // construct the polygon render geometry
        final boolean needsFill = this.fillColorA > 0.0f && this.numRenderPoints > 3;
        // polygon render geometry is valid if there was no update to the
        // linestring render points and we already have polygon render
        // triangles
        final boolean hasFill = !updated && (this.polyTriangles != null);

        if(needsFill != hasFill) {
            Unsafe.free(this.polyTriangles);
            this.polyTriangles = null;
            try {
                if (needsFill) {
                    // XXX - we currently always pass in a threshold if tessellated
                    //       rather than also checking 'needsTessellate'. this is
                    //       because the vertex data in the source exterior ring
                    //       could be sufficient tessellated already, but the
                    //       triangles derived from tessellating the polygon may
                    //       exceed the threshold. should look at computing an MBB
                    //       and comparing diagonal length with threshold for a
                    //       short circuit
                    this.polyTriangles = Tessellate.polygon(this.renderPoints,
                                                        24,
                                                        3,
                                                        this.numRenderPoints-1,
                                                        this.tessellated ? GLBatchLineString.threshold : 0d,
                                                        true);
                }
            } catch (Exception e) {
                // XXX - If release is called while the validateGeomtry is being
                // called which seems to be the case with both ATAK-11161 and
                // ATAK-11275, protect against a hard crash but note the exception.
                com.atakmap.coremap.log.Log.e(TAG, "XXX - freed / null renderPoint", e);
                return false;
            }
        }

        return updated;
    }

    @Override
    boolean projectVertices(GLMapView view, int vertices) {
        if(!super.projectVertices(view, vertices))
            return false;

        if(this.vertices == null) {
            Unsafe.free(this.polyVertices);
            this.polyVertices = null;
        }

        if(this.polyTriangles == null)
            return true;

        // XXX - ensure buffer capacity
        if(this.polyVertices == null || this.polyVertices.capacity() < this.polyTriangles.limit()) {
            if(this.polyVertices != null)
                Unsafe.free(this.polyVertices);
            this.polyVertices = Unsafe.allocateDirect(this.polyTriangles.limit(), FloatBuffer.class);
        }

        final double unwrap = view.idlHelper.getUnwrap(this.mbb);
        projectVerticesImpl(view, this.polyTriangles, this.polyTriangles.limit()/3, vertices, altitudeMode, unwrap, this.polyVertices, this.centroidProj);

        return true;
    }

    public void setGeometry(final Polygon polygon) {
        this.setGeometry(polygon, -1);
    }

    @Override
    protected void setGeometryImpl(Geometry geometry) {
        hasBeenExtruded = false;
        Polygon polygon = (Polygon)geometry;

        final int numRings = ((polygon.getExteriorRing() != null) ? 1 : 0) + polygon.getInteriorRings().size();
        if (numRings == 0) {
            this.numRenderPoints = 0;
        } else {
            super.setGeometryImpl(polygon.getExteriorRing());
        }
    }

    @Override
    public void draw(GLMapView view, int vertices) {
        this.projectVertices(view, vertices);
        final FloatBuffer v = this.vertices;
        if(v == null)
            return;

        if(this.fillColorA > 0f && this.polyTriangles != null) {
            ByteBuffer bb = Unsafe.allocateDirect(this.polyTriangles.limit()*4);
            bb.order(ByteOrder.nativeOrder());
            FloatBuffer toDraw = bb.asFloatBuffer();
            view.forward(polyTriangles, 3, toDraw, 3);


            GLES20FixedPipeline.glEnableClientState(GLES20FixedPipeline.GL_VERTEX_ARRAY);
            GLES20FixedPipeline.glEnable(GLES20FixedPipeline.GL_BLEND);
            GLES20FixedPipeline.glBlendFunc(GLES20FixedPipeline.GL_SRC_ALPHA,
                    GLES20FixedPipeline.GL_ONE_MINUS_SRC_ALPHA);

            GLES20FixedPipeline.glVertexPointer(3, GLES20FixedPipeline.GL_FLOAT, 0, toDraw);

            GLES20FixedPipeline.glColor4f(this.fillColorR,
                    this.fillColorG,
                    this.fillColorB,
                    this.fillColorA);

            GLES20FixedPipeline.glDrawArrays(GLES30.GL_TRIANGLES, 0,
                    toDraw.limit()/3);

            GLES20FixedPipeline.glDisable(GLES20FixedPipeline.GL_BLEND);
            GLES20FixedPipeline.glDisableClientState(GLES20FixedPipeline.GL_VERTEX_ARRAY);

            Unsafe.free(toDraw);
        }

        if (this.drawStroke)
            super.drawImpl(view, v, 3);

    }

    @Override
    protected void batchImpl(GLMapView view, GLRenderBatch2 batch, int renderPass, int vertices, int size, FloatBuffer v) {
        if(!MathUtils.hasBits(renderPass, GLMapView.RENDER_PASS_SURFACE))
            return;
        if (extrude == -1.0) {
            if (!hasBeenExtruded || extrudedPolygons == null
                    || extrudedOutline == null) {
                int last = points.limit() - 3;
                boolean closed = points.get(0) == points.get(last)
                        && points.get(1) == points.get(last + 1)
                        && points.get(2) == points.get(last + 2);
                hasBeenExtruded = true;
                double altitude = view.getTerrainMeshElevation(points.get(0), points.get(1));

                Unsafe.free(extrudedPolygons);
                extrudedPolygons = GLExtrude.extrudeRelative(altitude, points.get(2), points, 3, closed);

                Unsafe.free(projectedExtrudedPolygons);
                projectedExtrudedPolygons = Unsafe.allocateDirect(extrudedPolygons.limit(), FloatBuffer.class);
                extrudedPolygons.rewind();

                Unsafe.free(extrudedOutline);
                extrudedOutline = GLExtrude.extrudeOutline(altitude, points.get(2), points, 3, closed, false);

                Unsafe.free(projectedExtrudedOutline);
                projectedExtrudedOutline = Unsafe.allocateDirect(extrudedOutline.limit(), FloatBuffer.class);
                extrudedOutline.rewind();
            }
            final double unwrap = view.idlHelper.getUnwrap(this.mbb);
            projectVerticesImpl(view, extrudedPolygons, extrudedPolygons.limit()/3, vertices, Feature.AltitudeMode.Relative, unwrap, projectedExtrudedPolygons, this.centroidProj);
            if (this.fillColor == 0) {
                this.fillColorR = 1.0f;
                this.fillColorG = 1.0f;
                this.fillColorB = 1.0f;
                this.fillColorA = 0.5f;

            }
            batch.batch(-1,
                    GLES20FixedPipeline.GL_TRIANGLES,
                    3,
                    0, projectedExtrudedPolygons,
                    0, null,
                    this.fillColorR,
                    this.fillColorG,
                    this.fillColorB,
                    this.fillColorA);

            projectVerticesImpl(view, extrudedOutline, extrudedOutline.limit() / 3, vertices, Feature.AltitudeMode.Relative, unwrap, projectedExtrudedOutline, centroidProj);

            // It would be preferable to do this using glPolygonOffset, although that (would?)
            // require changes to how batching works.
            // This constant seems to work well for offsetting the outlines so they render on top of
            // the mesh, just a bit awkward to do it here.
            // NOTE: when multiple objects are near each other, the outlines of background objects
            // might appear above the nearby objects, this looks weird and is not preferable.
            double zOffset = -1.0e-4;
            view.scratch.matrix.setToTranslation(0, 0, zOffset);
            view.scratch.matrix.get(view.scratch.matrixD, Matrix.MatrixOrder.COLUMN_MAJOR);
            for(int i = 0; i < view.scratch.matrixF.length; ++i) {
                view.scratch.matrixF[i] = (float)view.scratch.matrixD[i];
            }

            // Make sure the lines appear relatively linear in size regardless of zoom level
            // using drawMapResolution on it's own (or it's reciprocal) had issues at the extremes,
            // raising to a power < 1/2 seems to work well.
            batch.setLineWidth(4.0f * (float)Math.pow(1.0 / view.drawMapResolution, 0.2));

            batch.pushMatrix(GLES20FixedPipeline.GL_MODELVIEW);
            batch.setMatrix(GLES20FixedPipeline.GL_MODELVIEW, view.scratch.matrixF, 0);
            // TODO: mess around with the outline color, maybe just make this white/black like the
            // the non-extruded KMLs?
            batch.batch(-1, GLES20FixedPipeline.GL_LINES,
                    3,
                    0, projectedExtrudedOutline,
                    0, null,
                    this.fillColorR * 0.9f,
                    this.fillColorG * 0.9f,
                    this.fillColorB * 0.9f,
                    1.0f);
            batch.popMatrix(GLES20FixedPipeline.GL_MODELVIEW);
            batch.setLineWidth(1);
        }
        else {
            if (this.fillColorA > 0 && this.polyTriangles != null) {
                batch.batch(-1,
                        GLES20FixedPipeline.GL_TRIANGLES,
                        size,
                        0, this.polyVertices,
                        0, null,
                        this.fillColorR,
                        this.fillColorG,
                        this.fillColorB,
                        this.fillColorA);
            }

            if(this.drawStroke) {
                super.batchImpl(view, batch, renderPass, vertices, size, v);
            }
        }
    }

    @Override
    public void release() {
        super.release();

        Unsafe.free(this.polyVertices);
        this.polyVertices = null;

        Unsafe.free(this.polyTriangles);
        this.polyTriangles = null;

        Unsafe.free(extrudedPolygons);
        extrudedPolygons = null;

        Unsafe.free(projectedExtrudedPolygons);
        projectedExtrudedPolygons = null;

        Unsafe.free(extrudedOutline);
        extrudedOutline = null;

        Unsafe.free(projectedExtrudedOutline);
        projectedExtrudedOutline = null;
    }
}
