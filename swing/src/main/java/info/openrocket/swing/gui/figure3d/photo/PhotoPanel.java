package info.openrocket.swing.gui.figure3d.photo;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.SplashScreen;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.EventObject;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.GLRunnable;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.awt.GLJPanel;
import com.jogamp.opengl.fixedfunc.GLLightingFunc;
import com.jogamp.opengl.fixedfunc.GLMatrixFunc;
import com.jogamp.opengl.glu.GLU;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.event.MouseInputAdapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jogamp.opengl.util.awt.AWTGLReadBufferUtil;

import info.openrocket.core.util.ORColor;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.events.DocumentChangeEvent;
import info.openrocket.core.document.events.DocumentChangeListener;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.startup.Application;
import info.openrocket.core.startup.Preferences;
import info.openrocket.core.util.Coordinate;
import info.openrocket.core.util.MathUtil;
import info.openrocket.core.util.StateChangeListener;

import info.openrocket.swing.gui.figure3d.RealisticRenderer;
import info.openrocket.swing.gui.figure3d.RocketRenderer;
import info.openrocket.swing.gui.figure3d.TextureCache;
import info.openrocket.swing.gui.figure3d.photo.exhaust.FlameRenderer;
import info.openrocket.swing.gui.main.Splash;

public class PhotoPanel extends JPanel implements GLEventListener {
	private static final long serialVersionUID = 1L;
	private static final Logger log = LoggerFactory.getLogger(PhotoPanel.class);

	static {
		// this allows the GL canvas and things like the motor selection
		// drop down to z-order themselves.
		JPopupMenu.setDefaultLightWeightPopupEnabled(false);
	}

	private FlightConfiguration configuration;
	private Component canvas;
	private TextureCache textureCache = new TextureCache();
	private double ratio;
	private boolean needUpdate = false;

	private List<ImageCallback> imageCallbacks = new java.util.Vector<>();

	private RocketRenderer rr;
	private PhotoSettings p;
	private OpenRocketDocument document;
	private DocumentChangeListener changeListener;

	interface ImageCallback {
		public void performAction(BufferedImage i);
	}

	void addImageCallback(ImageCallback a) {
		imageCallbacks.add(a);
		repaint();
	}

	void setDoc(final OpenRocketDocument doc) {
		document = doc;
		cachedBounds = null;
		this.configuration = doc.getSelectedConfiguration();

		changeListener = new DocumentChangeListener() {
			@Override
			public void documentChanged(DocumentChangeEvent event) {
				log.debug("Repainting on document change");
				configuration = doc.getSelectedConfiguration();
				needUpdate = true;
				PhotoPanel.this.repaint();
			}
		};
		document.addDocumentChangeListener(changeListener);

		((GLAutoDrawable) canvas).invoke(false, new GLRunnable() {
			@Override
			public boolean run(final GLAutoDrawable drawable) {
				rr = new RealisticRenderer(doc);
				rr.init(drawable);

				return false;
			}
		});
	}

	void clearDoc() {
		document.removeDocumentChangeListener(changeListener);
		changeListener = null;
		document = null;
	}

	PhotoSettings getSettings() {
		return p;
	}

	PhotoPanel(OpenRocketDocument document, PhotoSettings p) {
    	this.p = p;
		this.setLayout(new BorderLayout());
		PhotoPanel.this.configuration = document.getSelectedConfiguration();

		// Fixes a linux / X bug: Splash must be closed before GL Init
		SplashScreen splash = Splash.getSplashScreen();
		if (splash != null && splash.isVisible())
			splash.close();

		initGLCanvas();
		setupMouseListeners();

		p.addChangeListener(new StateChangeListener() {
			@Override
			public void stateChanged(EventObject e) {
				log.debug("Repainting on settings state change");
				PhotoPanel.this.repaint();
			}
		});

	}

	private void initGLCanvas() {
		try {
			log.debug("Setting up GL capabilities...");
			final GLProfile glp = GLProfile.get(GLProfile.GL2);

			final GLCapabilities caps = new GLCapabilities(glp);
			caps.setBackgroundOpaque(false);

			if (Application.getPreferences().getBoolean(
					Preferences.OPENGL_ENABLE_AA, true)) {
				caps.setSampleBuffers(true);
				caps.setNumSamples(6);
			} else {
				log.trace("GL - Not enabling AA by user pref");
			}

			if (Application.getPreferences().getBoolean(
					Preferences.OPENGL_USE_FBO, false)) {
				log.trace("GL - Creating GLJPanel");
				canvas = new GLJPanel(caps);
				((GLJPanel) canvas).setOpaque(false);
			} else {
				log.trace("GL - Creating GLCanvas");
				canvas = new GLCanvas(caps);
			}
			canvas.setBackground(new java.awt.Color(0, 0, 0, 0));

			((GLAutoDrawable) canvas).addGLEventListener(this);
			this.add(canvas, BorderLayout.CENTER);
		} catch (Throwable t) {
			log.error("An error occurred creating 3d View", t);
			canvas = null;
			this.add(new JLabel("Unable to load 3d Libraries: "
					+ t.getMessage()));
		}
	}

	private void setupMouseListeners() {
		MouseInputAdapter a = new MouseInputAdapter() {
			int lastX;
			int lastY;
			MouseEvent pressEvent;

			@Override
			public void mousePressed(final MouseEvent e) {
				lastX = e.getX();
				lastY = e.getY();
				pressEvent = e;
			}

			@Override
			public void mouseWheelMoved(MouseWheelEvent e) {
				p.setViewDistance(p.getViewDistance() + 0.1
						* e.getWheelRotation());
			}

			@Override
			public void mouseDragged(final MouseEvent e) {
				// You can get a drag without a press while a modal dialog is
				// shown
				if (pressEvent == null)
					return;

				final double height = canvas.getHeight();
				final double width = canvas.getWidth();
				final double x1 = (width - 2 * lastX) / width;
				final double y1 = (2 * lastY - height) / height;
				final double x2 = (width - 2 * e.getX()) / width;
				final double y2 = (2 * e.getY() - height) / height;

				p.setViewAltAz(p.getViewAlt() - (y1 - y2), p.getViewAz()
						+ (x1 - x2));

				lastX = e.getX();
				lastY = e.getY();
			}
		};

		canvas.addMouseWheelListener(a);
		canvas.addMouseMotionListener(a);
		canvas.addMouseListener(a);
	}

	@Override
	public void paintImmediately(Rectangle r) {
		super.paintImmediately(r);
		if (canvas != null)
			((GLAutoDrawable) canvas).display();
	}

	@Override
	public void paintImmediately(int x, int y, int w, int h) {
		super.paintImmediately(x, y, w, h);
		if (canvas != null)
			((GLAutoDrawable) canvas).display();
	}

	/*
	 * @Override public void repaint() { if (canvas != null) ((GLAutoDrawable)
	 * canvas).display(); super.repaint(); }
	 */
	@Override
	public void display(final GLAutoDrawable drawable) {
		GL2 gl = drawable.getGL().getGL2();

		if (needUpdate)
			rr.updateFigure(drawable);
		needUpdate = false;

		draw(drawable, 0, true);

		if (p.isMotionBlurred()) {
			Bounds b = calculateBounds();

			float m = .6f;
			int c = 10;
			float d = (float) b.xSize / 25.0f;

			gl.glAccum(GL2.GL_LOAD, m);

			for (int i = 1; i <= c; i++) {
				draw(drawable, d / c * i, true);
				gl.glAccum(GL2.GL_ACCUM, (1.0f - m) / c);
			}

			gl.glAccum(GL2.GL_RETURN, 1.0f);
		}

		if (!imageCallbacks.isEmpty()) {
			final BufferedImage i;
			// If off-screen rendering is disabled, and the sky color is transparent, we need to redraw the scene
			// in an off-screen framebuffer object (FBO), otherwise the fake transparency rendering will cause the
			// exported image to have a fully white background.
			if (!Application.getPreferences().getBoolean(
					Preferences.OPENGL_USE_FBO, false) && p.getSkyColorOpacity() < 100) {
				i = drawToBufferedImage(drawable);
			} else {
				i = (new AWTGLReadBufferUtil(
						GLProfile.get(GLProfile.GL2), true)) // Set the second parameter to true
						.readPixelsToBufferedImage(drawable.getGL(), 0, 0,
								drawable.getSurfaceWidth(), drawable.getSurfaceHeight(), true);
			}
			final Vector<ImageCallback> cbs = new Vector<>(
					imageCallbacks);
			imageCallbacks.clear();
			for (ImageCallback ia : cbs) {
				try {
					ia.performAction(i);
				} catch (Throwable t) {
					log.error("Image Callback {} threw", i, t);
				}
			}
		}
	}

	/**
	 * Draws the scene with fake transparency rendering disabled to an off-screen framebuffer object (FBO) and
	 * returns the result as a BufferedImage.
	 * @param drawable The GLAutoDrawable to draw to
	 * @return The rendered image
	 */
	private BufferedImage drawToBufferedImage(final GLAutoDrawable drawable) {
		GL2 gl = drawable.getGL().getGL2();
		int width = drawable.getSurfaceWidth();
		int height = drawable.getSurfaceHeight();

		// Create a new framebuffer object (FBO)
		int[] fboId = new int[1];
		gl.glGenFramebuffers(1, fboId, 0);
		gl.glBindFramebuffer(GL2.GL_FRAMEBUFFER, fboId[0]);

		// Create a texture to store the rendered image
		int[] textureId = new int[1];
		gl.glGenTextures(1, textureId, 0);
		gl.glBindTexture(GL.GL_TEXTURE_2D, textureId[0]);
		gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
		gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
		gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, width, height, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null);

		// Attach the texture to the FBO
		gl.glFramebufferTexture2D(GL2.GL_FRAMEBUFFER, GL2.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, textureId[0], 0);

		// Create a renderbuffer for depth and attach it to the FBO
		int[] depthRenderbuffer = new int[1];
		gl.glGenRenderbuffers(1, depthRenderbuffer, 0);
		gl.glBindRenderbuffer(GL.GL_RENDERBUFFER, depthRenderbuffer[0]);
		gl.glRenderbufferStorage(GL.GL_RENDERBUFFER, GL2.GL_DEPTH_COMPONENT, width, height);
		gl.glFramebufferRenderbuffer(GL2.GL_FRAMEBUFFER, GL2.GL_DEPTH_ATTACHMENT, GL.GL_RENDERBUFFER, depthRenderbuffer[0]);

		// Check if the FBO is complete
		int status = gl.glCheckFramebufferStatus(GL2.GL_FRAMEBUFFER);
		if (status != GL2.GL_FRAMEBUFFER_COMPLETE) {
			throw new RuntimeException("Framebuffer not complete");
		}

		// Draw the scene with useFakeTransparencyRendering set to false
		draw(drawable, 0, false);

		// Read the pixels from the FBO
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		gl.glReadPixels(0, 0, width, height, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, buffer);

		// Unbind the FBO and delete resources
		gl.glBindFramebuffer(GL2.GL_FRAMEBUFFER, 0);
		gl.glDeleteFramebuffers(1, fboId, 0);
		gl.glDeleteTextures(1, textureId, 0);
		gl.glDeleteRenderbuffers(1, depthRenderbuffer, 0);

		// Convert the ByteBuffer to a BufferedImage
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int i = (y * width + x) * 4;
				int r = buffer.get(i) & 0xFF;
				int g = buffer.get(i + 1) & 0xFF;
				int b = buffer.get(i + 2) & 0xFF;
				int a = buffer.get(i + 3) & 0xFF;
				image.setRGB(x, height - y - 1, (a << 24) | (r << 16) | (g << 8) | b);
			}
		}
		return image;
	}

	private static void convertColor(ORColor color, float[] out) {
		if (color == null) {
			out[0] = 1;
			out[1] = 1;
			out[2] = 0;
			out[3] = 1;
		} else {
			out[0] = (float) color.getRed() / 255f;
			out[1] = (float) color.getGreen() / 255f;
			out[2] = (float) color.getBlue() / 255f;
			out[3] = (float) color.getAlpha() / 255f;
		}
	}

	/**
	 * Blend two colors
	 * @param color1 first color to blend
	 * @param color2 second color to blend
	 * @param ratio blend ratio. 0 = full color 1, 0.5 = mid-blend, 1 = full color 2
	 * @return blended color
	 */
	private static ORColor blendColors(ORColor color1, ORColor color2, double ratio) {
		if (ratio < 0 || ratio > 1) {
			throw new IllegalArgumentException("Blend ratio must be between 0 and 1");
		}

		double inverseRatio = 1 - ratio;

		int r = (int) ((color1.getRed() * inverseRatio) + (color2.getRed() * ratio));
		int g = (int) ((color1.getGreen() * inverseRatio) + (color2.getGreen() * ratio));
		int b = (int) ((color1.getBlue() * inverseRatio) + (color2.getBlue() * ratio));
		int a = (int) ((color1.getAlpha() * inverseRatio) + (color2.getAlpha() * ratio));

		return new ORColor(r, g, b, a);
	}

	private void draw(final GLAutoDrawable drawable, float dx, boolean useFakeTransparencyRendering) {
		GL2 gl = drawable.getGL().getGL2();
		GLU glu = new GLU();

		float[] color = new float[4];

		gl.glEnable(GL.GL_MULTISAMPLE);

		convertColor(p.getSunlight(), color);
		float amb = (float) p.getAmbiance();
		float dif = 1.0f - amb;
		float spc = 1.0f;
		gl.glLightfv(
				GLLightingFunc.GL_LIGHT1,
				GLLightingFunc.GL_AMBIENT,
				new float[] { amb * color[0], amb * color[1], amb * color[2], 1 },
				0);
		gl.glLightfv(
				GLLightingFunc.GL_LIGHT1,
				GLLightingFunc.GL_DIFFUSE,
				new float[] { dif * color[0], dif * color[1], dif * color[2], 1 },
				0);
		gl.glLightfv(
				GLLightingFunc.GL_LIGHT1,
				GLLightingFunc.GL_SPECULAR,
				new float[] { spc * color[0], spc * color[1], spc * color[2], 1 },
				0);

		// Machines that don't use off-screen rendering can't render transparent background, so we create it
		// artificially by blending the sky color with white (= color that is rendered as transparent background)
		if (useFakeTransparencyRendering && !Application.getPreferences().getBoolean(
				Preferences.OPENGL_USE_FBO, false)) {
			convertColor(blendColors(p.getSkyColor(), new ORColor(255, 255, 255, 0), 1-p.getSkyColorOpacity()),
					color);
		} else {
			convertColor(p.getSkyColor(), color);
		}
		gl.glClearColor(color[0], color[1], color[2], color[3]);
		gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);

		gl.glMatrixMode(GLMatrixFunc.GL_PROJECTION);
		gl.glLoadIdentity();
		glu.gluPerspective(p.getFov() * (180.0 / Math.PI), ratio, 0.1f, 50f);
		gl.glMatrixMode(GLMatrixFunc.GL_MODELVIEW);

		// Flip textures for LEFT handed coords
		gl.glMatrixMode(GL.GL_TEXTURE);
		gl.glLoadIdentity();
		gl.glScaled(-1, 1, 1);
		gl.glTranslated(-1, 0, 0);

		gl.glMatrixMode(GLMatrixFunc.GL_MODELVIEW);
		gl.glLoadIdentity();

		gl.glEnable(GL.GL_CULL_FACE);
		gl.glCullFace(GL.GL_BACK);
		gl.glFrontFace(GL.GL_CCW);

		// Draw the sky
		gl.glPushMatrix();
		gl.glDisable(GLLightingFunc.GL_LIGHTING);
		gl.glDepthMask(false);
		gl.glRotated(p.getViewAlt() * (180.0 / Math.PI), 1, 0, 0);
		gl.glRotated(p.getViewAz() * (180.0 / Math.PI), 0, 1, 0);
		gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
		if (p.getSky() != null) {
			p.getSky().draw(gl, textureCache);
		}
		gl.glDepthMask(true);
		gl.glEnable(GLLightingFunc.GL_LIGHTING);
		gl.glPopMatrix();

		if (rr == null)
			return;

		glu.gluLookAt(0, 0, p.getViewDistance(), 0, 0, 0, 0, 1, 0);
		gl.glRotated(p.getViewAlt() * (180.0 / Math.PI), 1, 0, 0);
		gl.glRotated(p.getViewAz() * (180.0 / Math.PI), 0, 1, 0);

		float[] lightPosition = new float[] {
				(float) Math.cos(p.getLightAlt())
						* (float) Math.sin(p.getLightAz()),//
				(float) Math.sin(p.getLightAlt()),//
				(float) Math.cos(p.getLightAlt())
						* (float) Math.cos(p.getLightAz()), //
				0 };

		gl.glLightfv(GLLightingFunc.GL_LIGHT1, GLLightingFunc.GL_POSITION,
				lightPosition, 0);

		// Change to LEFT Handed coordinates
		gl.glScaled(1, 1, -1);
		gl.glFrontFace(GL.GL_CW);
		setupModel(gl);

		gl.glTranslated(dx - p.getAdvance(), 0, 0);

		if (p.isFlame() && configuration.hasMotors()) {
			convertColor(p.getFlameColor(), color);

			gl.glLightfv(GLLightingFunc.GL_LIGHT2, GLLightingFunc.GL_AMBIENT,
					new float[] { 0, 0, 0, 1 }, 0);
			gl.glLightfv(GLLightingFunc.GL_LIGHT2, GLLightingFunc.GL_DIFFUSE,
					new float[] { color[0], color[1], color[2], 1 }, 0);
			gl.glLightfv(GLLightingFunc.GL_LIGHT2, GLLightingFunc.GL_SPECULAR,
					new float[] { color[0], color[1], color[2], 1 }, 0);

			Bounds b = calculateBounds();
			gl.glLightf(GLLightingFunc.GL_LIGHT2,
					GLLightingFunc.GL_QUADRATIC_ATTENUATION, 20f);
			gl.glLightfv(GLLightingFunc.GL_LIGHT2, GLLightingFunc.GL_POSITION,
					new float[] { (float) (b.xMax + .1f), 0, 0, 1 }, 0);
			gl.glEnable(GLLightingFunc.GL_LIGHT2);
		} else {
			gl.glDisable(GLLightingFunc.GL_LIGHT2);
			gl.glLightfv(GLLightingFunc.GL_LIGHT2, GLLightingFunc.GL_DIFFUSE,
					new float[] { 0, 0, 0, 1 }, 0);
		}

		rr.render(drawable, configuration, new HashSet<>());

		//Figure out the lowest stage shown

		AxialStage bottomStage = configuration.getBottomStage();
		int bottomStageNumber = 0;
		if (bottomStage != null)
			bottomStageNumber = bottomStage.getStageNumber();
		//final int currentStageNumber = configuration.getActiveStages()[configuration.getActiveStages().length-1];
		//final AxialStage currentStage = (AxialStage)configuration.getRocket().getChild( bottomStageNumber);

		final FlightConfigurationId motorID = configuration.getFlightConfigurationID();



		final Iterator<MotorConfiguration> iter = configuration.getActiveMotors().iterator();
		while( iter.hasNext()){
			MotorConfiguration curConfig = iter.next();
			final MotorMount mount = curConfig.getMount();
			int curStageNumber = ((RocketComponent)mount).getStageNumber();

			//If this mount is not in currentStage continue on to the next one.
			if( curStageNumber != bottomStageNumber ){
				continue;
			}

			final Motor motor = mount.getMotorConfig(motorID).getMotor();
			final double length = motor.getLength();

			Coordinate[] position = ((RocketComponent) mount)
					.toAbsolute(new Coordinate(((RocketComponent) mount)
							.getLength() + mount.getMotorOverhang() - length));

			for (Coordinate coordinate : position) {
				gl.glPushMatrix();
				gl.glTranslated(coordinate.x + motor.getLength(),
						coordinate.y, coordinate.z);
				FlameRenderer.drawExhaust(gl, p, motor);
				gl.glPopMatrix();
			}
		}

		gl.glDisable(GL.GL_BLEND);
		gl.glFrontFace(GL.GL_CCW);
	}

	@Override
	public void dispose(final GLAutoDrawable drawable) {
		log.trace("GL - dispose() called");
		if (rr != null)
			rr.dispose(drawable);
		textureCache.dispose(drawable);
	}

	@Override
	public void init(final GLAutoDrawable drawable) {
		log.trace("GL - init()");
		//drawable.setGL(new DebugGL2(drawable.getGL().getGL2()));

		final GL2 gl = drawable.getGL().getGL2();

		gl.glClearDepth(1.0f); // clear z-buffer to the farthest
		gl.glDepthFunc(GL.GL_LESS); // the type of depth test to do

		textureCache.init(drawable);

		// gl.glDisable(GLLightingFunc.GL_LIGHT1);

		FlameRenderer.init(gl);

	}

	@Override
	public void reshape(final GLAutoDrawable drawable, final int x,
			final int y, final int w, final int h) {
		log.trace("GL - reshape()");
		ratio = (double) w / (double) h;
	}

	@SuppressWarnings("unused")
	private static class Bounds {
		double xMin, xMax, xSize;
		double yMin, yMax, ySize;
		double zMin, zMax, zSize;
		double rMax;
	}

	private Bounds cachedBounds = null;

	/**
	 * Calculates the bounds for the current configuration
	 * 
	 * @return
	 */
	private Bounds calculateBounds() {
		if (cachedBounds != null) {
			return cachedBounds;
		} else {
			final Bounds b = new Bounds();
			final Collection<Coordinate> bounds = configuration.getBounds();
			for (Coordinate c : bounds) {
				b.xMax = Math.max(b.xMax, c.x);
				b.xMin = Math.min(b.xMin, c.x);

				b.yMax = Math.max(b.yMax, c.y);
				b.yMin = Math.min(b.yMin, c.y);

				b.zMax = Math.max(b.zMax, c.z);
				b.zMin = Math.min(b.zMin, c.z);

				double r = MathUtil.hypot(c.y, c.z);
				b.rMax = Math.max(b.rMax, r);
			}
			b.xSize = b.xMax - b.xMin;
			b.ySize = b.yMax - b.yMin;
			b.zSize = b.zMax - b.zMin;
			cachedBounds = b;
			return b;
		}
	}

	private void setupModel(final GL2 gl) {
		// Get the bounds
		final Bounds b = calculateBounds();
		gl.glRotated(-p.getPitch() * (180.0 / Math.PI), 0, 0, 1);
		gl.glRotated(p.getYaw() * (180.0 / Math.PI), 0, 1, 0);
		gl.glRotated(p.getRoll() * (180.0 / Math.PI), 1, 0, 0);
		// Center the rocket in the view.
		gl.glTranslated(-b.xMin - b.xSize / 2.0, 0, 0);
	}

}