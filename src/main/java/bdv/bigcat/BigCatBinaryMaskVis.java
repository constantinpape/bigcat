package bdv.bigcat;

import java.awt.Cursor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import javax.swing.JOptionPane;

import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.io.InputTriggerDescription;
import org.scijava.ui.behaviour.io.yaml.YamlConfigIO;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Context;
import org.zeromq.ZMQ.Socket;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import bdv.BigDataViewer;
import bdv.bigcat.annotation.AnnotationsHdf5Store;
import bdv.bigcat.composite.ARGBCompositeAlphaYCbCr;
import bdv.bigcat.composite.Composite;
import bdv.bigcat.composite.CompositeCopy;
import bdv.bigcat.control.AnnotationsController;
import bdv.bigcat.control.ConfirmSegmentController;
import bdv.bigcat.control.DrawProjectAndIntersectController;
import bdv.bigcat.control.LabelBrushController;
import bdv.bigcat.control.LabelFillController;
import bdv.bigcat.control.LabelPersistenceController;
import bdv.bigcat.control.MergeController;
import bdv.bigcat.control.SelectionController;
import bdv.bigcat.control.SendPaintedLabelsToSolver;
import bdv.bigcat.control.SolverMessages;
import bdv.bigcat.control.SolverMessages.Annotation;
import bdv.bigcat.control.SolverMessages.Start;
import bdv.bigcat.control.SolverMessages.Type;
import bdv.bigcat.control.SolverMessages.Wrapper;
import bdv.bigcat.control.TranslateZController;
import bdv.bigcat.label.FragmentSegmentAssignment;
import bdv.bigcat.label.PairLabelMultiSetLongIdPicker;
import bdv.bigcat.ui.ARGBConvertedLabelPairSource;
import bdv.bigcat.ui.GoldenAngleSaturatedConfirmSwitchARGBStream;
import bdv.bigcat.ui.Util;
import bdv.img.SetCache;
import bdv.img.h5.AbstractH5SetupImageLoader;
import bdv.img.h5.H5LabelMultisetSetupImageLoader;
import bdv.img.h5.H5UnsignedByteSetupImageLoader;
import bdv.img.h5.H5Utils;
import bdv.labels.labelset.Label;
import bdv.labels.labelset.LabelMultisetType;
import bdv.labels.labelset.Multiset;
import bdv.labels.labelset.VolatileLabelMultisetType;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.IdService;
import bdv.util.LocalIdService;
import bdv.viewer.TriggerBehaviourBindings;
import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.IHDF5Reader;
import gnu.trove.map.hash.TLongLongHashMap;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.neighborhood.DiamondShape;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.cell.CellImg;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.view.RandomAccessiblePair;
import net.imglib2.view.Views;

public class BigCatBinaryMaskVis
{
	final static private int[] cellDimensions = new int[]{ 64, 64, 8 };

	private H5LabelMultisetSetupImageLoader fragments = null;
	private ARGBConvertedLabelPairSource convertedLabelPair = null;
	private CellImg< LongType, ?, ? > paintedLabels = null;
	private BigDataViewer bdv;
	private GoldenAngleSaturatedConfirmSwitchARGBStream colorStream;
	private FragmentSegmentAssignment assignment;
	private final String projectFile;
	private final String paintedLabelsDataset;
	private final String mergedLabelsDataset;
	private String fragmentSegmentLutDataset;
	private LabelPersistenceController persistenceController;
	private AnnotationsController annotationsController;
	private final InputTriggerConfig config;

	private final IdService idService = new LocalIdService();

	public static void main( final String[] args ) throws Exception
	{
		new BigCatBinaryMaskVis( args );
	}

	public BigCatBinaryMaskVis( final String[] args ) throws Exception
	{
		Util.initUI();

		this.config = getInputTriggerConfig();

		projectFile = args[ 0 ];

		String labelsDataset = "neuron_ids";
		if ( args.length > 1 )
		{
			labelsDataset = args[ 1 ];
		}

		String rawDataset = "raw";
		if ( args.length > 2 )
		{
			rawDataset = args[ 2 ];
		}

		fragmentSegmentLutDataset = "/fragment_segment_lut";
		if ( args.length > 3 )
		{
			fragmentSegmentLutDataset = args[ 3 ];
		}

		System.out.println( "Opening " + projectFile );
		final IHDF5Reader reader = HDF5Factory.open( projectFile );

		// support both file_format 0.0 and >=0.1
		final String volumesPath = reader.isGroup( "/volumes" ) ? "/volumes" : "";
		final String labelsPath = reader.isGroup( volumesPath + "/labels" ) ? volumesPath + "/labels" : "";

		/* raw pixels */
		final String rawPath = volumesPath + "/" + rawDataset;
		final H5UnsignedByteSetupImageLoader raw = new H5UnsignedByteSetupImageLoader( reader, rawPath, 0, cellDimensions );

		/* fragments */
		String fragmentsPath = labelsPath + "/" + labelsDataset;
		mergedLabelsDataset = labelsPath + "/merged_" + labelsDataset;
		paintedLabelsDataset = labelsPath + "/painted_" + labelsDataset;
		fragmentsPath = reader.object().isDataSet( mergedLabelsDataset ) ? mergedLabelsDataset : fragmentsPath;
		if ( reader.exists( fragmentsPath ) )
		{
			readFragments( args, reader, fragmentsPath, paintedLabelsDataset, fragmentSegmentLutDataset );
		}
		else
		{
			System.out.println( "no labels found cooresponding to requested dataset '" + labelsDataset + "' (searched in '" + labelsPath + "')" );
		}

		setupBdv( raw );
	}

	private void readFragments(
			final String[] args,
			final IHDF5Reader reader,
			final String labelsDataset,
			final String paintedLabelsDataset,
			final String fragmentSegmentLutDataset ) throws IOException
	{
		fragments =
				new H5LabelMultisetSetupImageLoader(
						reader,
						null,
						labelsDataset,
						1,
						cellDimensions );
		final RandomAccessibleInterval< VolatileLabelMultisetType > fragmentsPixels = fragments.getVolatileImage( 0, 0 );
		final long[] fragmentsDimensions = Intervals.dimensionsAsLongArray( fragmentsPixels );

		final Long nextIdObject = H5Utils.loadAttribute( reader, "/", "next_id" );
		long maxId = 0;
		if ( nextIdObject == null )
		{
			maxId = maxId( reader, labelsDataset, maxId );
			if ( reader.exists( paintedLabelsDataset ) )
			{
				maxId = maxId( reader, paintedLabelsDataset, maxId );
			}
		}
		else
		{
			maxId = nextIdObject.longValue() - 1;
		}

		idService.invalidate( maxId );

		final String paintedLabelsFilePath = args[ 0 ];
		final File paintedLabelsFile = new File( paintedLabelsFilePath );
		if ( paintedLabelsFile.exists() && reader.exists( paintedLabelsDataset ) )
		{
			paintedLabels = H5Utils.loadUnsignedLong( new File( paintedLabelsFilePath ), paintedLabelsDataset, cellDimensions );
		}
		else
		{
			paintedLabels = new CellImgFactory< LongType >( cellDimensions ).create( fragmentsDimensions, new LongType() );
			for ( final LongType t : paintedLabels )
			{
				t.set( Label.TRANSPARENT );
			}
		}

		/* pair labels */
		final RandomAccessiblePair< VolatileLabelMultisetType, LongType > labelPair =
				new RandomAccessiblePair<>(
						fragments.getVolatileImage( 0, 0 ),
						paintedLabels );

		assignment = new FragmentSegmentAssignment( idService );
		final TLongLongHashMap lut = H5Utils.loadLongLongLut( reader, fragmentSegmentLutDataset, 1024 );
		if ( lut != null )
		{
			assignment.initLut( lut );
		}

		colorStream = new GoldenAngleSaturatedConfirmSwitchARGBStream( assignment );
		colorStream.setAlpha( 0x20 );
		convertedLabelPair =
				new ARGBConvertedLabelPairSource(
						3,
						labelPair,
						paintedLabels, // as Interval, used just for the size
						fragments.getMipmapTransforms(),
						colorStream );
	}

	@SuppressWarnings( "unchecked" )
	private void setupBdv( final H5UnsignedByteSetupImageLoader raw ) throws Exception
	{
		/* composites */
		final ArrayList< Composite< ARGBType, ARGBType > > composites = new ArrayList< >();
		composites.add( new CompositeCopy< ARGBType >() );

		final String windowTitle = "BigCAT";

		if ( fragments != null )
		{
			composites.add( new ARGBCompositeAlphaYCbCr() );
			//composites.add( new ARGBCompositeAlphaMultiply() );
			bdv = Util.createViewer(
					windowTitle,
					new AbstractH5SetupImageLoader[]{ raw },
					new ARGBConvertedLabelPairSource[]{ convertedLabelPair },
					new SetCache[]{ fragments },
					composites,
					config );
		}
		else
		{
			bdv = Util.createViewer(
					windowTitle,
					new AbstractH5SetupImageLoader[]{ raw },
					new ARGBConvertedLabelPairSource[]{},
					new SetCache[]{},
					composites,
					config );
		}

		bdv.getViewerFrame().setVisible( true );

		final TriggerBehaviourBindings bindings = bdv.getViewerFrame().getTriggerbindings();

		final SelectionController selectionController;
		final LabelBrushController brushController;
		final PairLabelMultiSetLongIdPicker idPicker;

		if ( fragments != null )
		{
			idPicker = new PairLabelMultiSetLongIdPicker(
					bdv.getViewer(),
					RealViews.affineReal(
							Views.interpolate(
									new RandomAccessiblePair< >(
											Views.extendValue(
													fragments.getImage( 0 ),
													new LabelMultisetType() ),
											Views.extendValue(
													paintedLabels,
													new LongType( Label.OUTSIDE) ) ),
									new NearestNeighborInterpolatorFactory< Pair< LabelMultisetType, LongType > >() ),
							fragments.getMipmapTransforms()[ 0 ] )
					);

			selectionController = new SelectionController(
					bdv.getViewer(),
					idPicker,
					colorStream,
					idService,
					assignment,
					config,
					bdv.getViewerFrame().getKeybindings(),
					config);

			final MergeController mergeController = new MergeController(
					bdv.getViewer(),
					idPicker,
					selectionController,
					assignment,
					config,
					bdv.getViewerFrame().getKeybindings(),
					config);

			brushController = new LabelBrushController(
					bdv.getViewer(),
					paintedLabels,
					fragments.getMipmapTransforms()[ 0 ],
					assignment,
					selectionController,
					projectFile,
					paintedLabelsDataset,
					cellDimensions,
					config);

			persistenceController = new LabelPersistenceController(
					bdv.getViewer(),
					fragments.getImage( 0 ),
					paintedLabels,
					assignment,
					idService,
					projectFile,
					paintedLabelsDataset,
					mergedLabelsDataset,
					cellDimensions,
					fragmentSegmentLutDataset,
					config,
					bdv.getViewerFrame().getKeybindings() );

			final LabelFillController fillController = new LabelFillController(
					bdv.getViewer(),
					fragments.getImage( 0 ),
					paintedLabels,
					fragments.getMipmapTransforms()[ 0 ],
					assignment,
					selectionController,
					new DiamondShape( 1 ),
					idPicker,
					config);

			/* splitter (and more) */
			final DrawProjectAndIntersectController dpi = new DrawProjectAndIntersectController(
					bdv,
					idService,
					new AffineTransform3D(),
					new InputTriggerConfig(),
					fragments.getImage(0),
					paintedLabels,
					fragments.getMipmapTransforms()[0],
					assignment,
					colorStream,
					selectionController,
					bdv.getViewerFrame().getKeybindings(),
					bindings,
					"shift T" );

			final ConfirmSegmentController confirmSegment = new ConfirmSegmentController(
					bdv.getViewer(),
					selectionController,
					assignment,
					colorStream,
					colorStream,
					config,
					bdv.getViewerFrame().getKeybindings() );

			final Context context = ZMQ.context( 1 );
			final Socket socket = context.socket( ZMQ.PUB );
			final Socket receiver = context.socket( ZMQ.SUB );

			socket.bind( "inproc://#1" );
			receiver.connect( "inproc://#1" );
			receiver.subscribe( new byte[ 0 ] );

			final Thread t = new Thread( () -> {
				// TODO Auto-generated method stub
				System.out.println( "Started thread!" );
				final String START_STRING = "start";
				final String STOP_STRING = "stop";
				boolean startedTransmission = false;
				boolean receivedBoundingbox = false;
				long label = -1;

				RandomAccessibleInterval< LongType > img = null;
				Bdv bdv = null;
//				new ImageJ();

				while ( !Thread.currentThread().isInterrupted() )
				{
					Wrapper message = null;
					try
					{
						message = SolverMessages.Wrapper.parseFrom( receiver.recv() );
					}
					catch ( final InvalidProtocolBufferException e )
					{
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					if ( !( message == null ) )
					{
						if ( startedTransmission )
						{
							if ( message.getType() == SolverMessages.Type.STOP )
							{
								startedTransmission = false;
								receivedBoundingbox = false;
								bdv = BdvFunctions.show( img, "mask" );

								bdv.getBdvHandle().getSetupAssignments().getMinMaxGroups().iterator().next().setRange( ( int ) label - 2, ( int ) label + 2 );
								label = -1;
//							ImageJFunctions.show( img );
							}
							else if ( message.getType() == SolverMessages.Type.ANNOTATION )
							{

								final Annotation annotation = message.getAnnotation();

								final long[] cellMin = new long[ 3 ];
								final long[] cellMax = new long[ 3 ];

//							final ByteBuffer buf = ByteBuffer.wrap( message );

//							for ( final long[] m : new long[][] { cellMin, cellMax } )
//							{
								for ( int d = 0; d < 3; ++d )
								{
									cellMin[ d ] = annotation.getMin( d );
									cellMax[ d ] = annotation.getMax( d );
								}
//							}

								label = annotation.getId() / 10000; // buf.getLong()
								// / 10000;

								System.out.println( "RECEIVED LABEL: " + label );

								final RandomAccess< LongType > ra = img.randomAccess();

								final byte BYTE_ONE = ( byte ) 1;

								final ByteString bs = annotation.getData();
								int count = 0;

								for ( long z = cellMin[ 2 ]; z <= cellMax[ 2 ]; ++z )
								{
									ra.setPosition( z, 2 );
									for ( long y = cellMin[ 1 ]; y <= cellMax[ 1 ]; ++y )
									{
										ra.setPosition( y, 1 );
										for ( long x = cellMin[ 0 ]; x <= cellMax[ 0 ]; ++x )
										{
											ra.setPosition( x, 0 );
											ra.get().set( bs.byteAt( count++ ) == BYTE_ONE ? label : label - 1 );
//										if ( buf.get() == BYTE_ONE )
//										{
//											ra.get().set( label );
//										}
										}

									}
								}

							}

						}
						else if ( message.getType() == Type.START )
						{
							final long[] min = new long[ 3 ];
							final long[] max = new long[ 3 ];

							final Start start = message.getStart();

							for ( int d = 0; d < 3; ++d )
							{
								min[ d ] = start.getMin( d );
								max[ d ] = start.getMax( d );
							}

							final long[] dim = new long[ 3 ];

							for ( int d = 0; d < 3; ++d )
							{
								dim[ d ] = max[ d ] - min[ d ] + 1;
							}

							img = Views.translate( ArrayImgs.longs( dim ), min );
							for ( final LongType i : Views.iterable( img ) )
							{
								i.set( label - 2 );
							}
							if ( bdv != null )
							{
								bdv.close();
							}
							receivedBoundingbox = true;

							startedTransmission = true;
						}
					}
				}
			} );

			t.start();


			final SendPaintedLabelsToSolver sendLabels = new SendPaintedLabelsToSolver(
					bdv.getViewer(),
					fragments.getImage( 0 ),
					paintedLabels,
					fragments.getMipmapTransforms()[ 0 ],
					new int[] { 64, 64, 32 },
					config,
					socket );

			bindings.addBehaviourMap( "select", selectionController.getBehaviourMap() );
			bindings.addInputTriggerMap( "select", selectionController.getInputTriggerMap() );

			bindings.addBehaviourMap( "merge", mergeController.getBehaviourMap() );
			bindings.addInputTriggerMap( "merge", mergeController.getInputTriggerMap() );

			bindings.addBehaviourMap( "brush", brushController.getBehaviourMap() );
			bindings.addInputTriggerMap( "brush", brushController.getInputTriggerMap() );

			bindings.addBehaviourMap( "fill", fillController.getBehaviourMap() );
			bindings.addInputTriggerMap( "fill", fillController.getInputTriggerMap() );

			bindings.addBehaviourMap( "send", sendLabels.getBehaviourMap() );
			bindings.addInputTriggerMap( "send", sendLabels.getInputTriggerMap() );

			bdv.getViewerFrame().addWindowListener( new WindowAdapter()
			{
				@Override
				public void windowClosing( final WindowEvent we )
				{
					saveBeforeClosing();
					System.exit( 0 );
				}
			} );
		}
		else
		{
			selectionController = null;
			brushController = null;
			idPicker = null;
		}

		final TranslateZController translateZController = new TranslateZController(
				bdv.getViewer(),
				raw.getMipmapResolutions()[ 0 ],
				config );
		bindings.addBehaviourMap( "translate_z", translateZController.getBehaviourMap() );

		final AnnotationsHdf5Store annotationsStore = new AnnotationsHdf5Store( projectFile, idService );
		annotationsController = new AnnotationsController(
				annotationsStore,
				bdv,
				idService,
				config,
				bdv.getViewerFrame().getKeybindings(),
				config );

		bindings.addBehaviourMap( "annotation", annotationsController.getBehaviourMap() );
		bindings.addInputTriggerMap( "annotation", annotationsController.getInputTriggerMap() );

		/* overlays */
		bdv.getViewer().getDisplay().addOverlayRenderer( annotationsController.getAnnotationOverlay() );

		if ( brushController != null )
		{
			bdv.getViewer().getDisplay().addOverlayRenderer( brushController.getBrushOverlay() );
		}

		if ( selectionController != null )
		{
			bdv.getViewer().getDisplay().addOverlayRenderer( selectionController.getSelectionOverlay() );
		}
	}

	protected InputTriggerConfig getInputTriggerConfig() throws IllegalArgumentException {

		final String[] filenames = {
				"bigcatkeyconfig.yaml",
				System.getProperty( "user.home" ) + "/.bdv/bigcatkeyconfig.yaml"
		};

		for (final String filename : filenames) {

			try {
				if (new File(filename).isFile()) {
					System.out.println("reading key config from file " + filename);
					return new InputTriggerConfig(YamlConfigIO.read(filename));
				}
			} catch (final IOException e) {
				System.err.println("Error reading " + filename);
			}
		}

		System.out.println("creating default input trigger config");

		// default input trigger config, disables "control button1" drag in bdv
		// (collides with default of "move annotation")
		final InputTriggerConfig config = new InputTriggerConfig(
				Arrays.asList(
						new InputTriggerDescription[] {
								new InputTriggerDescription( new String[] { "not mapped" }, "drag rotate slow", "bdv" )
						}
						)
				);

		return config;
	}

	public void saveBeforeClosing()
	{
		final String message = "Save changes to " + projectFile + " before closing?";

		if (
				JOptionPane.showConfirmDialog(
						bdv.getViewerFrame(),
						message,
						bdv.getViewerFrame().getTitle(),
						JOptionPane.YES_NO_OPTION ) == JOptionPane.YES_OPTION )
		{
			bdv.getViewerFrame().setCursor( Cursor.getPredefinedCursor( Cursor.WAIT_CURSOR ) );
			annotationsController.saveAnnotations();
			persistenceController.saveNextId();
			persistenceController.saveFragmentSegmentAssignment();
			persistenceController.savePaintedLabels();
		}
	}

	final static protected long maxId(
			final IHDF5Reader reader,
			final String labelsDataset,
			long maxId ) throws IOException
	{
		final H5LabelMultisetSetupImageLoader labelLoader =
				new H5LabelMultisetSetupImageLoader(
						reader,
						null,
						labelsDataset,
						1,
						cellDimensions );

		for ( final LabelMultisetType t : Views.iterable( labelLoader.getImage( 0 ) ) )
		{
			for ( final Multiset.Entry< Label > v : t.entrySet() )
			{
				final long id = v.getElement().id();
				if ( id != Label.TRANSPARENT && IdService.greaterThan( id, maxId ) )
				{
					maxId = id;
				}
			}
		}

		return maxId;
	}
}
