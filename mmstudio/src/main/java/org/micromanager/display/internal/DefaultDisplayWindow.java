///////////////////////////////////////////////////////////////////////////////
//PROJECT:       Micro-Manager
//SUBSYSTEM:     Display implementation
//-----------------------------------------------------------------------------
//
// AUTHOR:       Chris Weisiger, 2015
//
// COPYRIGHT:    University of California, San Francisco, 2015
//
// LICENSE:      This file is distributed under the BSD license.
//               License text is included with the source distribution.
//
//               This file is distributed in the hope that it will be useful,
//               but WITHOUT ANY WARRANTY; without even the implied warranty
//               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//
//               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
//               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
//               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.

package org.micromanager.display.internal;

import com.google.common.eventbus.DeadEvent;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import ij.ImagePlus;
import ij.gui.ImageWindow;
import ij.io.FileInfo;
import ij.measure.Calibration;
import ij.Menus;
import ij.WindowManager;

import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;


import java.util.ArrayList;
import java.util.List;

import javax.swing.event.MouseInputAdapter;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import net.miginfocom.swing.MigLayout;

import org.micromanager.data.Coords;
import org.micromanager.data.Datastore;
import org.micromanager.data.DatastoreSavedEvent;
import org.micromanager.data.Image;
import org.micromanager.data.NewImageEvent;
import org.micromanager.data.NewSummaryMetadataEvent;
import org.micromanager.data.SummaryMetadata;
import org.micromanager.display.ControlsFactory;
import org.micromanager.display.DisplaySettings;
import org.micromanager.display.DisplayWindow;
import org.micromanager.display.RequestToDrawEvent;
import org.micromanager.events.DatastoreClosingEvent;

import org.micromanager.data.internal.DefaultCoords;

import org.micromanager.events.internal.DefaultEventManager;

import org.micromanager.display.internal.events.DefaultDisplayAboutToShowEvent;
import org.micromanager.display.internal.events.DefaultNewDisplayEvent;
import org.micromanager.display.internal.events.DefaultNewImagePlusEvent;
import org.micromanager.display.internal.events.DefaultRequestToDrawEvent;
import org.micromanager.display.internal.events.DisplayActivatedEvent;
import org.micromanager.display.internal.events.FullScreenEvent;
import org.micromanager.display.internal.events.LayoutChangedEvent;
import org.micromanager.display.internal.events.NewDisplaySettingsEvent;
import org.micromanager.display.internal.events.RequestToCloseEvent;
import org.micromanager.display.internal.events.StatusEvent;
import org.micromanager.display.internal.inspector.InspectorFrame;
import org.micromanager.display.internal.link.DisplayGroupManager;


import org.micromanager.internal.utils.GUIUtils;
import org.micromanager.internal.utils.JavaUtils;
import org.micromanager.internal.utils.MMFrame;
import org.micromanager.internal.utils.ReportingUtils;


/**
 * This class is the window that handles image viewing: it contains the
 * canvas and controls for determining which channel, Z-slice, etc. is shown. 
 * Note that it is *not* an ImageJ ImageWindow; instead, it creates a
 * DummyImageWindow instance for liaising with ImageJ. See that class for
 * more information on why we do this.
 * TODO: this class is getting kind of unwieldy-huge, and should probably be
 * refactored.
 */
public class DefaultDisplayWindow extends MMFrame implements DisplayWindow {

   // HACK: the first time a DisplayWindow is created, create an
   // InspectorFrame to go with it.
   static {
      new InspectorFrame(null);
   }

   private final Datastore store_;
   private DisplaySettings displaySettings_;
   private MMVirtualStack stack_;
   private ImagePlus ijImage_;
   private final EventBus displayBus_;

   // This will be our intermediary with ImageJ.
   private DummyImageWindow dummyWindow_;

   // Properties related to fullscreen mode.
   private JFrame fullScreenFrame_;

   // Used to generate custom display controls.
   private final ControlsFactory controlsFactory_;

   // GUI components
   private JPanel contentsPanel_;
   private JPanel canvasPanel_;
   private MMImageCanvas canvas_;
   private JPanel controlsPanel_;
   private HyperstackControls hyperstackControls_;

   private final Object guiLock_ = new Object();
   private boolean haveCreatedGUI_ = false;

   // Used by the pack() method to track changes in our size.
   private Dimension prevControlsSize_;

   private CanvasUpdateThread canvasThread_;

   private boolean haveClosed_ = false;

   // Custom string in the title.
   private String customName_;

   /**
    * Factory method to create a new DisplayWindow with default
    * DisplaySettings and title. See the main createDisplay below for
    * more info on parameters.
    */
   public static DefaultDisplayWindow createDisplay(Datastore store,
         ControlsFactory factory) {
      return createDisplay(store, factory, null, null);
   }

   /**
    * Create a new DefaultDisplayWindow. Use this method instead of calling
    * the constructor directly, as some setup needs to be performed after
    * the display has completed its constructor.
    * @param store The Datastore this display will show images for.
    * @param factory A ControlsFactory to create any extra controls for this
    *        display. May be null, in which case there will be no extra
    *        controls
    * @param settings The DisplaySettings to use for this display. May
    *        be null, in which case default settings will be pulled from the
    *        user's profile.
    * @param title A custom title. May be null, in which case the title will
    *        be "MM image display".
    * @return The new DisplayWindow in a ready-to-use state.
    */
   public static DefaultDisplayWindow createDisplay(Datastore store,
         ControlsFactory factory, DisplaySettings settings, String title) {
      DefaultDisplayWindow result = new DefaultDisplayWindow(store, factory,
            settings, title);
      // There's a race condition here: if the Datastore adds an image
      // between us registering and us manually checking for images, then
      // we risk creating GUI objects twice, so makeGUI() has to be coded
      // defensively to avoid double-calls.
      store.registerForEvents(result);
      if (store.getNumImages() > 0) {
         result.makeGUI();
      }
      DefaultEventManager.getInstance().post(
            new DefaultNewDisplayEvent(result));
      DefaultEventManager.getInstance().registerForEvents(result);
      return result;
   }

   /**
    * @param controlsFactory ControlsFactory to generate any custom controls.
    *        May be null if the creator does not want any.
    * @param settings DisplaySettings to use as initial state for this display
    * @param customName Custom title to show in title bar, or null for none.
    */
   private DefaultDisplayWindow(Datastore store,
         ControlsFactory controlsFactory, DisplaySettings settings,
         String customName) {
      super("image display window");
      store_ = store;
      if (settings == null) {
         displaySettings_ = DefaultDisplaySettings.getStandardSettings();
      }
      else {
         displaySettings_ = settings;
      }
      customName_ = customName;
      displayBus_ = new EventBus();
      displayBus_.register(this);
      controlsFactory_ = controlsFactory;

      DisplayGroupManager.getInstance().addDisplay(this);

      final DefaultDisplayWindow thisWindow = this;
      // Post an event whenever we're made active, so that the InspectorFrame
      // can update its contents.
      addWindowListener(new WindowAdapter() {
         @Override
         public void windowActivated(WindowEvent e) {
            DefaultEventManager.getInstance().post(
               new DisplayActivatedEvent(thisWindow));
         }
      });

      // HACK: on OSX, we want to show the ImageJ menubar for our windows.
      // However, if we simply do setMenuBar(Menus.getMenuBar()), then somehow
      // ImageJ *loses* the menubar: it, and the items in it, can only be
      // attached to one window at a time, apparently. So we have to put it
      // back when we lose focus.
      if (JavaUtils.isMac()) {
         addWindowListener(new WindowAdapter() {
            @Override
            public void windowActivated(WindowEvent e) {
               // Steal the menubar from ImageJ.
               setMenuBar(Menus.getMenuBar());
            }

            @Override
            public void windowDeactivated(WindowEvent e) {
               // Find the primary ImageJ window and give it its menubar back.
               for (Frame f : Frame.getFrames()) {
                  if (f instanceof ij.ImageJ) {
                     f.setMenuBar(getMenuBar());
                     break;
                  }
               }
            }
         });
      }
   }

   /**
    * Now that there's at least one image in the Datastore, we need to create
    * our UI and the objects we'll use to communicate with ImageJ. Actual
    * construction is spun off into the EDT (Event Dispatch Thread), unless
    * of course we're already in that thread.
    */
   public void makeGUI() {
      synchronized(guiLock_) {
         if (haveCreatedGUI_) {
            // Already done this.
            return;
         }
         if (!SwingUtilities.isEventDispatchThread()) {
            try {
               SwingUtilities.invokeAndWait(new Runnable() {
                  @Override
                  public void run() {
                     makeGUI_EDTSafe();
                  }
               });
            }
            catch (InterruptedException e) {
               // This should never happen.
               ReportingUtils.showError(e,
                     "Interrupted while creating DisplayWindow");
            }
            catch (java.lang.reflect.InvocationTargetException e) {
               ReportingUtils.showError(e,
                     "Exception while creating DisplayWindow");
            }
         }
         else {
            makeGUI_EDTSafe();
         }
      }
   }

   /**
    * This method should only be called from makeGUI, which
    * ensures that this method is only called from within the EDT.
    */
   private void makeGUI_EDTSafe() {
      loadAndRestorePosition(getLocation().x, getLocation().y);
      ijImage_ = new MMImagePlus();
      setImagePlusMetadata(ijImage_);
      stack_ = new MMVirtualStack(store_, displayBus_, ijImage_);
      ijImage_.setStack(getName(), stack_);
      ijImage_.setOpenAsHyperStack(true);
      displayBus_.post(new DefaultNewImagePlusEvent(this, ijImage_));
      // The ImagePlus object needs to be pseudo-polymorphic, depending on
      // the number of channels in the Datastore. However, we may not have
      // all of the channels available to us at the time this display is
      // created, so we may need to re-create things down the road.
      if (store_.getAxisLength(Coords.CHANNEL) > 1) {
         // Have multiple channels.
         shiftToCompositeImage();
      }
      if (ijImage_ instanceof MMCompositeImage) {
         ((MMCompositeImage) ijImage_).reset();
      }

      // Make the canvas thread before any of our other control objects,
      // which may perform draw requests that need to be processed.
      haltCanvasThread();
      canvasThread_ = new CanvasUpdateThread(store_, stack_, ijImage_,
            this);
      canvasThread_.start();

      makeWindowControls();
      // This needs to be done after the canvas is created, but before we
      // call zoomToPreferredSize.
      dummyWindow_ = DummyImageWindow.makeWindow(ijImage_, this);
      zoomToPreferredSize();
      setVisible(true);

      addWindowListener(new WindowAdapter() {
         @Override
         public void windowClosing(WindowEvent event) {
            requestToClose();
         }
      });
      setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

      // Set us to draw the first image in the dataset.
      // TODO: potentially there could be no image at these Coords, though
      // that seems unlikely. Such an edge case isn't all that harmful
      // anyway; we'll just display a blank image until the user adjusts the
      // display to an image that does exist.
      DefaultCoords.Builder builder = new DefaultCoords.Builder();
      for (String axis : store_.getAxes()) {
         builder.index(axis, 0);
      }
      setDisplayedImageTo(builder.build());

      // Must set this before we call resetTitle(), which checks it.
      haveCreatedGUI_ = true;
      resetTitle();

      setWindowSize();

      DefaultEventManager.getInstance().post(new DefaultDisplayAboutToShowEvent(this));
   }

   /**
    * [Re]generate the controls for adjusting the display, showing metadata,
    * etc.
    */
   private void makeWindowControls() {
      if (contentsPanel_ == null) {
         contentsPanel_ = new JPanel();
      }
      contentsPanel_.removeAll();
      contentsPanel_.setLayout(new MigLayout("insets 1, fillx, filly",
         "[grow, fill]", "[grow, fill]related[]"));

      recreateCanvas();
      contentsPanel_.add(canvasPanel_, "align center, wrap, grow");

      if (controlsPanel_ == null) {
         controlsPanel_ = new JPanel(new MigLayout("insets 0, fillx"));
      }
      controlsPanel_.removeAll();
      if (hyperstackControls_ == null) {
         hyperstackControls_ = new HyperstackControls(store_, stack_, this,
               false);
      }
      controlsPanel_.add(hyperstackControls_,
            "align center, span, growx, wrap");
      controlsPanel_.add(new ButtonPanel(this, controlsFactory_));

      contentsPanel_.add(controlsPanel_, "align center, wrap, growx, growy 0");

      add(contentsPanel_);
      Insets insets = getInsets();
      Dimension size = contentsPanel_.getMinimumSize();
      setMinimumSize(new Dimension(
            insets.left + insets.right + size.width,
            insets.top + insets.bottom + size.height));
      pack();
   }

   /**
    * Re-generate our image canvas and canvas panel, along with resize logic.
    */
   private void recreateCanvas() {
      canvas_ = new MMImageCanvas(ijImage_, this);
      
      // HACK: set the minimum size. If we don't do this, then the canvas
      // doesn't shrink properly when the window size is reduced. Why?!
      canvas_.setMinimumSize(new Dimension(16, 16));
      Double mag = displaySettings_.getMagnification();
      canvas_.setMagnification(mag != null ? mag : 1);
      // Wrap the canvas in a subpanel so that we can get events when it
      // resizes.
      canvasPanel_ = new JPanel(new MigLayout("insets 0, fill"));
      canvasPanel_.add(canvas_, "align center");

      // Propagate resizing to the canvas, adjusting the view rectangle.
      canvasPanel_.addComponentListener(new ComponentAdapter() {
         @Override
         public void componentResized(ComponentEvent e) {
            Dimension panelSize = canvasPanel_.getSize();
            canvas_.updateSize(panelSize);
            Dimension canvasSize = canvas_.getSize();
            // The canvas may now be smaller than the panel containing it (e.g.
            // if the panel is a rectangle containing a square canvas); shrink
            // the panel to suit so we don't blow the window as a whole
            // out-of-size.
            if (canvasSize.width < panelSize.width ||
               canvasSize.height < panelSize.height) {
               canvasPanel_.setSize(canvasSize);
            }
         }
      });

      // Add a listener so we can update the histogram when an ROI is drawn.
      canvas_.addMouseListener(new MouseInputAdapter() {
         @Override
         public void mouseReleased(MouseEvent me) {
            ijImage_.updateAndDraw();
         }
      });

      if (displaySettings_.getMagnification() == null) {
         // Grab the canvas's magnification.
         displaySettings_ = displaySettings_.copy()
            .magnification(canvas_.getMagnification())
            .build();
         displayBus_.post(new NewDisplaySettingsEvent(displaySettings_, this));
      }
   }

   /**
    * In addition to the display's name, we also append magnification and
    * save status.
    * This method is public so that DisplayGroupManager can force a re-set
    * of the title when other displays are created or destroyed (and our
    * display number is shown/hidden).
    */
   public void resetTitle() {
      if (!haveCreatedGUI_) {
         // No window to adjust yet.
         return;
      }
      String title = getName();
      title += String.format(" (%d%%)",
            (int) (canvas_.getMagnification() * 100));
      // HACK: don't display save status for the snap/live view.
      if (!title.contains("Snap/Live")) {
         if (store_.getSavePath() == null) {
            title += " (Not yet saved)";
         }
         else {
            title += " (Saved)";
         }
      }
      // Don't mindlessly change the title, as calling setStack can
      // potentially create new ImageJ windows that we don't want if called
      // in rapid succession (for unknown reasons that would require
      // a better understanding of the ImageJ codebase).
      if (!title.contentEquals(getTitle())) {
         // Don't have multiple threads adjusting the GUI at the same time.
         synchronized(this) {
            setTitle(title);
            // Ensure that ImageJ's opinion of our name reflects our own; this
            // is important for ImageJ's "Windows" menu.
            ijImage_.setStack(getName(), stack_);
         }
      }
   }

   /**
    * Set our canvas' magnification based on the preferred window magnification.
    */
   public void zoomToPreferredSize() {
      Point location = getLocation();

      double mag = displaySettings_.getMagnification();

      // Use approximation here because ImageJ has fixed allowed magnification
      // levels and we want to be able to be a bit more approximate and snap
      // to the closest allowed magnification. 
      if (mag < canvas_.getMagnification()) {
         while (mag < canvas_.getMagnification() &&
               Math.abs(mag - canvas_.getMagnification()) > .01) {
            canvas_.zoomOut(canvas_.getWidth() / 2, canvas_.getHeight() / 2);
         }
      } else if (mag > canvas_.getMagnification()) {
         while (mag > canvas_.getMagnification() &&
               Math.abs(mag - canvas_.getMagnification()) > .01) {
            canvas_.zoomIn(canvas_.getWidth() / 2, canvas_.getHeight() / 2);
         }
      }

      //Make sure the window is fully on the screen
      Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
      Point newLocation = new Point(location.x,location.y);
      if (newLocation.x + getWidth() > screenSize.width && getWidth() < screenSize.width) {
          newLocation.x = screenSize.width - getWidth();
      }
      if (newLocation.y + getHeight() > screenSize.height && getHeight() < screenSize.height) {
          newLocation.y = screenSize.height - getHeight();
      }

      setLocation(newLocation);
   }

   /**
    * We've discovered that we need to represent a multichannel image.
    */
   private void shiftToCompositeImage() {
      // Don't want to run this from a separate thread when we're in the middle
      // of building our GUI, e.g. because a second image arrived while we're
      // still responding to the first one.
      synchronized(this) {
         // Don't draw anything while we're doing this -- and we'll need a
         // new thread later anyway.
         haltCanvasThread();
         // TODO: assuming mode 1 for now.
         ijImage_ = new MMCompositeImage(ijImage_, 1, ijImage_.getTitle());
         ijImage_.setOpenAsHyperStack(true);
         MMCompositeImage composite = (MMCompositeImage) ijImage_;
         int numChannels = store_.getAxisLength(Coords.CHANNEL);
         composite.setNChannelsUnverified(numChannels);
         composite.reset();
         setImagePlusMetadata(ijImage_);
         canvasThread_ = new CanvasUpdateThread(store_, stack_, ijImage_,
               this);
         canvasThread_.start();
      }
      displayBus_.post(new DefaultNewImagePlusEvent(this, ijImage_));
   }

   /**
    * Tell our canvas update thread, if it exists, to cease all actions, then
    * wait for the thread to exit.
    */
   private void haltCanvasThread() {
      if (canvasThread_ != null) {
         canvasThread_.stopDisplayUpdates();
         try {
            canvasThread_.join();
         }
         catch (InterruptedException e) {
            ReportingUtils.logError(e, "Interrupted while waiting for canvas update thread to terminate");
         }
      }
   }

   /**
    * Tell the ImagePlus about certain properties of our data that it doesn't
    * otherwise know how to access.
    * TODO: this should maybe live in DummyImageWindow?
    */
   private void setImagePlusMetadata(ImagePlus plus) {
      try {
         Calibration cal = new Calibration(plus);
         cal.setUnit("um");
         // TODO: ImageJ only allows for one pixel size across all images, even
         // if e.g. different channels actually vary.
         // On the flipside, we only allow for square pixels, so we aren't
         // exactly perfect either.
         Image sample = store_.getAnyImage();
         if (sample == null) {
            ReportingUtils.logError("Unable to get an image for setting ImageJ metadata properties");
            return;
         }
         Double pixelSize = sample.getMetadata().getPixelSizeUm();
         if (pixelSize != null) {
            cal.pixelWidth = pixelSize;
            cal.pixelHeight = pixelSize;
         }
         SummaryMetadata summary = store_.getSummaryMetadata();
         if (summary.getWaitInterval() != null) {
            cal.frameInterval = summary.getWaitInterval() / 1000.0;
         }
         if (summary.getZStepUm() != null) {
            cal.pixelDepth = summary.getZStepUm();
         }
         plus.setCalibration(cal);

         FileInfo info = new FileInfo();
         info.directory = summary.getDirectory();
         info.fileName = summary.getName();
         info.width = sample.getWidth();
         info.height = sample.getHeight();
         ijImage_.setFileInfo(info);
      }
      catch (Exception e) {
         ReportingUtils.logError(e, "Error setting metadata");
      }
   }

   /**   
    * Our layout has changed and we need to repack.
    */         
   @Subscribe
   public void onLayoutChanged(LayoutChangedEvent event) {
      try {
         validate();
         pack();
      }
      catch (Exception e) {
         ReportingUtils.logError(e, "Error processing layout-changed event");
      }
   }

   /**
    * Something on our display bus (i.e. not the Datastore bus) wants us to
    * redisplay.
    */
   @Subscribe
   public void onDrawEvent(RequestToDrawEvent event) {
      try {
         Coords drawCoords = stack_.getCurrentImageCoords();
         if (event.getCoords() != null) {
            // In particular, they want to display this image.
            drawCoords = event.getCoords();
         }
         setDisplayedImageTo(drawCoords);
      }
      catch (Exception e) {
         ReportingUtils.logError(e, "Couldn't process RequestToDrawEvent");
      }
   }

   /**
    * Manually display the image at the specified coordinates.
    */
   @Override
   public void setDisplayedImageTo(Coords coords) {
      canvasThread_.addCoords(coords);
   }

   /**
    * Request a redraw of the displayed image(s).
    */
   @Override
   public void requestRedraw() {
      canvasThread_.addCoords(stack_.getCurrentImageCoords());
   }

   @Override
   public void displayStatusString(String status) {
      displayBus_.post(new StatusEvent(status));
   }

   @Override
   public void adjustZoom(double factor) {
      setMagnification(getMagnification() * factor);
   }

   @Override
   public void setMagnification(double magnification) {
      setDisplaySettings(displaySettings_.copy().magnification(magnification).build());
   }

   @Override
   public double getMagnification() {
      return canvas_.getMagnification();
   }
      
   @Override
   public Datastore getDatastore() {
      return store_;
   }

   // TODO: ideally we should not need this exposed (and definitely not
   // exposed in the API, as it's an implementation detail). Find out what
   // our users need this method for and find a better way.
   public MMVirtualStack getStack() {
      return stack_;
   }

   @Override
   public DisplaySettings getDisplaySettings() {
      return displaySettings_;
   }

   @Override
   public void setDisplaySettings(DisplaySettings settings) {
      displaySettings_ = settings;
      boolean magChanged = (settings.getMagnification() != null &&
            settings.getMagnification() != canvas_.getMagnification());
      // This will cause the canvas to pick up magnification changes, note.
      displayBus_.post(new NewDisplaySettingsEvent(settings, this));
      DefaultDisplaySettings.setStandardSettings(settings);
      if (haveCreatedGUI_) {
         if (magChanged) {
            // Ensure that any changes in the canvas size (and thus in our
            // window size) properly adjust other elements.
            setWindowSize();
         }
         // Assume any change in display settings will necessitate a redraw.
         displayBus_.post(new DefaultRequestToDrawEvent(null));
         // And the magnification may have changed.
         resetTitle();
      }
   }

   // TODO: this method assumes we're in Composite view mode.
   @Override
   public List<Image> getDisplayedImages() {
      ArrayList<Image> result = new ArrayList<Image>();
      Coords curCoords = stack_.getCurrentImageCoords();
      for (int i = 0; i < store_.getAxisLength("channel"); ++i) {
         Image tmp = store_.getImage(curCoords.copy().index("channel", i).build());
         if (tmp != null) {
            result.add(tmp);
         }
      }
      if (result.size() == 0) {
         // No "channel" axis; just return the current image.
         Image tmp = store_.getImage(curCoords);
         if (tmp != null) {
            result.add(tmp);
         }
      }
      return result;
   }

   @Override
   public ImagePlus getImagePlus() {
      return ijImage_;
   }

   @Override
   public boolean requestToClose() {
      displayBus_.post(new RequestToCloseEvent(this));
      return getIsClosed();
   }

   /**
    * This exists to catch RequestToCloseEvents that nobody is listening for,
    * which can happen when displays are duplicated. If we didn't do this, then
    * our display would be impossible to get rid of.
    */
   @Subscribe
   public void onDeadEvent(DeadEvent event) {
      if (event.getEvent() instanceof RequestToCloseEvent) {
         forceClosed();
      }
   }

   @Override
   public void forceClosed() {
      if (haveClosed_) {
         // Only ever call this method once.
         return;
      }
      savePosition();
      displayBus_.post(new DisplayDestroyedEvent(this));
      DefaultEventManager.getInstance().unregisterForEvents(this);
      store_.unregisterForEvents(this);
      dispose();
      haveClosed_ = true;
   }

   @Override
   public synchronized boolean getIsClosed() {
      // TODO: is this a proper indicator for if the window is closed?
      return (!isVisible() && fullScreenFrame_ == null);
   }

   /**
    * Turn fullscreen mode on or off. Fullscreen is actually a separate
    * frame due to how Java handles the GUI.
    * TODO: should this really be exposed in the API?
    */
   @Override
   public synchronized void toggleFullScreen() {
      // If the canvas decides to update while we are changing to/from
      // fullscreen mode, then bad things can happen, so we kill the canvas
      // thread first.
      haltCanvasThread();
      if (fullScreenFrame_ != null) {
         // We're currently fullscreen, and our fullscreen frame needs to go
         // away now. Retrieve our contents from it first, of course.
         add(contentsPanel_);
         fullScreenFrame_.dispose();
         fullScreenFrame_ = null;
         setWindowSize();
         setVisible(true);
      }
      else {
         // Transfer our contents to a new JFrame for the fullscreen mode.
         setVisible(false);
         fullScreenFrame_ = new JFrame();
         fullScreenFrame_.setUndecorated(true);
         fullScreenFrame_.setBounds(
               GUIUtils.getFullScreenBounds(getScreenConfig()));
         fullScreenFrame_.setExtendedState(JFrame.MAXIMIZED_BOTH);
         fullScreenFrame_.setResizable(false);
         fullScreenFrame_.add(contentsPanel_);
         fullScreenFrame_.setVisible(true);
      }
      canvasThread_ = new CanvasUpdateThread(store_, stack_, ijImage_, this);
      canvasThread_.start();
      displayBus_.post(
            new FullScreenEvent(getScreenConfig(), fullScreenFrame_ != null));
   }

   @Override
   public GraphicsConfiguration getScreenConfig() {
      Point p = getLocation();
      GraphicsConfiguration result = GUIUtils.getGraphicsConfigurationContaining(p.x, p.y);
      // HACK: if our corner is invalid, try using our center point.
      if (result == null) {
         Dimension dims = getSize();
         result = GUIUtils.getGraphicsConfigurationContaining(
               p.x + dims.width / 2, p.y + dims.height / 2);
      }
      return result;
   }

   @Override
   public ImageWindow getImageWindow() {
      return dummyWindow_;
   }

   @Override
   public Window getAsWindow() {
      return (Window) this;
   }

   @Override
   public void registerForEvents(Object obj) {
      displayBus_.register(obj);
   }

   @Override
   public void unregisterForEvents(Object obj) {
      displayBus_.unregister(obj);
   }

   @Override
   public void postEvent(Object obj) {
      displayBus_.post(obj);
   }

   @Override
   public EventBus getDisplayBus() {
      return displayBus_;
   }

   /**
    * Datastore has received a new image; display it, and adjust our
    * ImageJ object if necessary.
    */
   @Subscribe
   public void onNewImage(final NewImageEvent event) {
      try {
         if (!haveCreatedGUI_) {
            // Time to make our components, but we should only do so in the EDT.
            final DefaultDisplayWindow thisWindow = this;
            try {
               GUIUtils.invokeAndWait(new Runnable() {
                  @Override
                  public void run() {
                     thisWindow.makeGUI();
                  }
               });
            }
            catch (InterruptedException e) {
               ReportingUtils.logError(e, "Couldn't make window controls");
            }
            catch (java.lang.reflect.InvocationTargetException e) {
               ReportingUtils.logError(e, "Couldn't make window controls");
            }
         }
         receiveNewImage(event.getImage());
      }
      catch (Exception e) {
         ReportingUtils.logError(e, "Error processing new image");
      }
   }

   /**
    * Process a new image.
    */
   private void receiveNewImage(Image image) {
      try {
         // Check if we're transitioning from grayscale to multi-channel at this
         // time.
         int imageChannel = image.getCoords().getIndex("channel");
         if (!(ijImage_ instanceof MMCompositeImage) &&
               imageChannel > 0) {
            // Have multiple channels.
            shiftToCompositeImage();
            makeWindowControls();
         }
         if (ijImage_ instanceof MMCompositeImage) {
            // Verify that ImageJ has the right number of channels.
            int numChannels = store_.getAxisLength("channel");
            MMCompositeImage composite = (MMCompositeImage) ijImage_;
            composite.setNChannelsUnverified(numChannels);
            composite.reset();
         }
      }
      catch (Exception e) {
         ReportingUtils.logError(e, "Couldn't display new image");
      }
   }

   /**
    * When the summary metadata changes, make certain that certain values
    * get propagated to ImageJ.
    */
   @Subscribe
   public void onNewSummaryMetadata(NewSummaryMetadataEvent event) {
      if (ijImage_ != null) { // I.e. we've finished initializing
         setImagePlusMetadata(ijImage_);
      }
   }

   /**
    * When our Datastore goes away, we automatically close ourselves.
    */
   @Subscribe
   public void onDatastoreClosed(DatastoreClosingEvent event) {
      if (event.getDatastore() == store_) {
         forceClosed();
      }
   }

   /**
    * When our Datastore saves, we save our display settings, and update our
    * title.
    */
   @Subscribe
   public void onDatastoreSaved(DatastoreSavedEvent event) {
      try {
         String path = event.getPath();
         displaySettings_.save(path);
         resetTitle();
      }
      catch (Exception e) {
         ReportingUtils.logError(e, "Failed to respond to datastore saved event");
      }
   }

   /**
    * If a DisplayWindow is topmost, return that DisplayWindow; otherwise
    * return null. This relies on ImageJ's tracking of what the topmost window
    * is (NB Java does not have any method for determining the Z order of
    * a window, so any implementation of this method would require manual
    * tracking by *someone*).
    */
   public static DisplayWindow getCurrentWindow() {
      ImageWindow current = WindowManager.getCurrentWindow();
      if (current instanceof DummyImageWindow) {
         return ((DummyImageWindow) current).getMaster();
      }
      return null;
   }

   /**
    * Retrieve a list of all open DisplayWindows. We do this by iterating over
    * all ImageJ windows, checking to see if they are DummyImageWindows, and if
    * so, retrieving the master window for that DummyImageWindow.
    */
   public static List<DisplayWindow> getAllImageWindows() {
      ArrayList<DisplayWindow> result = new ArrayList<DisplayWindow>();
      int[] plusIDs = WindowManager.getIDList();
      if (plusIDs == null) {
         // Assume no displays have been created yet.
         return result;
      }
      for (int id : plusIDs) {
         ImagePlus plus = WindowManager.getImage(id);
         ImageWindow window = plus.getWindow();
         if (window instanceof DummyImageWindow) {
            result.add(((DummyImageWindow) window).getMaster());
         }
      }
      return result;
   }

   @Override
   public String getName() {
      String name = customName_;
      if (name == null) {
         // Use the filename instead.
         name = store_.getSummaryMetadata().getName();
      }
      if (name == null || name.contentEquals("")) {
         // Use a fallback name.
         name = "MM image display";
      }
      List<DisplayWindow> displays = DisplayGroupManager.getDisplaysForDatastore(store_);
      if (displays.size() > 1) {
         // Append a number so we can tell different displays for the
         // same datastore apart.
         for (int i = 0; i < displays.size(); ++i) {
            if (displays.get(i) == this) {
               name = String.format("#%d: %s", i + 1, name);
               break;
            }
         }
      }
      return name;
   }

   @Override
   public void setCustomTitle(String title) {
      customName_ = title;
      resetTitle();
   }

   @Override
   public DisplayWindow duplicate() {
      DisplayWindow result = createDisplay(store_, controlsFactory_);
      result.setDisplaySettings(displaySettings_);
      result.setCustomTitle(customName_);
      return result;
   }

   // Implemented to help out DummyImageWindow.
   public MMImageCanvas getCanvas() {
      return canvas_;
   }

   /**
    * Set our window size so that it precisely holds all components, or, if
    * there's not enough room to hold the entire canvas, expand to as large as
    * possible. This is conceptually similar to the override of the pack()
    * method, below, but in the opposite direction.
    */
   private synchronized void setWindowSize() {
      if (fullScreenFrame_ != null) {
         // Do nothing for now since we aren't visible anyway.
         return;
      }
      Dimension controlsSize = controlsPanel_.getPreferredSize();
      Image image = store_.getAnyImage();
      if (image == null || canvas_ == null) {
         // Nothing we can do here.
         ReportingUtils.logError("No image/canvas available with which to set window size (" + image + " and " + canvas_ + ")");
         return;
      }
      Dimension imageSize = new Dimension(
            (int) Math.ceil(image.getWidth() * canvas_.getMagnification()),
            (int) Math.ceil(image.getHeight() * canvas_.getMagnification()));
      // HACK: account for changes in the zoom that change the desired size
      // of the image canvas.
      canvas_.setDrawingSize(imageSize.width, imageSize.height);
      Insets insets = contentsPanel_.getInsets();
      Dimension screenSize = getScreenConfig().getBounds().getSize();
      // TODO: if we don't apply some padding here then we end up with the
      // canvas being a bit too small; no idea why.
      // The extra size ought to go away when we pack, anyway.
      int maxWidth = Math.min(screenSize.width,
            imageSize.width + insets.left + insets.right);
      int maxHeight = Math.min(screenSize.height,
            imageSize.height + controlsSize.height + insets.top + insets.bottom + 10);
      contentsPanel_.setSize(new Dimension(maxWidth, maxHeight));
      pack();
   }

   /**
    * HACK HACK HACK etc you get the idea.
    * Manually derive the size of components based on our own size. We have
    * a layout that looks roughly like this:
    * +---------+
    * |         |
    * |  canvas |
    * |         |
    * |         |
    * |         |
    * +---------+
    * |         |
    * | controls|
    * +---------+
    * The size of the controls can only grow vertically; the canvas can grow in
    * both dimensions, and should absorb all remaining extra space.
    * Unfortunately, canvas sizing is complicated by the fact that the canvas
    * has a "zoom mode" for when there isn't enough room to display the entire
    * image at the current zoom level.
    */
   @Override
   public synchronized void pack() {
      if (!haveCreatedGUI_) {
         // No point doing this until GUI creation is complete.
         return;
      }
      Dimension controlsSize = controlsPanel_.getPreferredSize();
      Dimension ourSize = contentsPanel_.getSize();
      Dimension screenSize = getScreenConfig().getBounds().getSize();
      // HACK: coerce our size to not exceed that of the screen we are in. No
      // idea how this happens in the first place, but it does with very large
      // images (e.g. 2.5k x 2.5k).
      ourSize = new Dimension(Math.min(ourSize.width, screenSize.width),
            Math.min(ourSize.height, screenSize.height));
      boolean isFullScreen = (fullScreenFrame_ != null);
      if (isFullScreen) {
         // Substitute the size of the monitor our contents are in.
         ourSize = GUIUtils.getFullScreenBounds(getScreenConfig()).getSize();
      }
      // Determine if a given component is growing (we need to increase our
      // own size) or shrinking (we need to shrink).
      int widthDelta = 0;
      int heightDelta = 0;
      if (prevControlsSize_ != null) {
         heightDelta += controlsSize.height - prevControlsSize_.height;
      }
      prevControlsSize_ = controlsSize;

      // Resize the canvas to use available spare space.
      // HACK: for some reason, we're off by 2 in width and 10 in height.
      int spareWidth = ourSize.width + widthDelta - 2;
      int spareHeight = ourSize.height + heightDelta - controlsSize.height - 10;
      Dimension panelSize = canvasPanel_.getSize();
      if (panelSize.width != spareWidth || panelSize.height != spareHeight) {
         canvasPanel_.setSize(spareWidth, spareHeight);
      }
      // Don't adjust the window size when in fullscreen mode.
      if (isFullScreen) {
         // HACK: override preferred size of contents panel so that
         // frame doesn't shrink when we repack it.
         contentsPanel_.setPreferredSize(ourSize);
         fullScreenFrame_.pack();
      }
      else {
         // Undo damage to contentsPanel_'s preferred size in the other branch.
         contentsPanel_.setPreferredSize(null);
         setSize(ourSize.width + widthDelta,
               ourSize.height + heightDelta);
         super.pack();
      }
   }

   @Override
   public String toString() {
      return String.format("<DefaultDisplayWindow named %s with unique ID %s>", getName(), hashCode());
   }
}