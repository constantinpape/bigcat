package bdv.img.h5;

import java.util.Arrays;

import bdv.img.cache.CacheArrayLoader;
import bdv.labels.labelset.LabelMultisetEntry;
import bdv.labels.labelset.LabelMultisetEntryList;
import bdv.labels.labelset.LongMappedAccess;
import bdv.labels.labelset.LongMappedAccessData;
import bdv.labels.labelset.VolatileLabelMultisetArray;
import ch.systemsx.cisd.base.mdarray.MDIntArray;
import ch.systemsx.cisd.hdf5.IHDF5IntReader;
import ch.systemsx.cisd.hdf5.IHDF5Reader;
import gnu.trove.impl.Constants;
import gnu.trove.map.hash.TLongIntHashMap;

/**
 * {@link CacheArrayLoader} for
 * Jan Funke's and other's h5 files
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class H5IntLabelMultisetArrayLoader extends AbstractH5LabelMultisetArrayLoader
{
	final private IHDF5IntReader reader;

	final private IHDF5IntReader scaleReader;

	public H5IntLabelMultisetArrayLoader(
			final IHDF5Reader reader,
			final IHDF5Reader scaleReader,
			final String dataset )
	{
		super( dataset );
		this.reader = reader.int32();
		this.scaleReader = ( scaleReader == null ) ? null : scaleReader.uint32();
	}

	@Override
	public int getBytesPerElement()
	{
		return 4;
	}

	@Override
	public VolatileLabelMultisetArray loadArray(
			final int timepoint,
			final int setup,
			final int level,
			final int[] dimensions,
			final long[] min ) throws InterruptedException
	{
		if ( level == 0 )
			return loadArrayLevel0( dimensions, min );

		final String listsPath = String.format( "l%02d/z%05d/y%05d/x%05d/lists", level, min[ 2 ], min[ 1 ], min[ 0 ] );
		final String dataPath = String.format( "l%02d/z%05d/y%05d/x%05d/data", level, min[ 2 ], min[ 1 ], min[ 0 ] );

		final int[] offsets = scaleReader.readMDArray( dataPath ).getAsFlatArray();
		final int[] lists = scaleReader.readArray( listsPath );
		final LongMappedAccessData listData = LongMappedAccessData.factory.createStorage( lists.length * 4 );
		final LongMappedAccess access = listData.createAccess();
		for ( int i = 0; i < lists.length; ++i )
			access.putInt( lists[ i ], i * 4 );
		return new VolatileLabelMultisetArray( offsets, listData, 0, true );
	}

	public VolatileLabelMultisetArray loadArrayLevel0(
			final int[] dimensions,
			final long[] min ) throws InterruptedException
	{
		int[] data = null;

		final MDIntArray block = reader.readMDArrayBlockWithOffset(
				dataset,
				new int[]{ dimensions[ 2 ], dimensions[ 1 ], dimensions[ 0 ] },
				new long[]{ min[ 2 ], min[ 1 ], min[ 0 ] } );

		data = block.getAsFlatArray();

		if ( data == null )
		{
			System.out.println(
					"H5 short label multiset array loader failed loading min = " +
					Arrays.toString( min ) +
					", dimensions = " +
					Arrays.toString( dimensions ) );

			data = new int[ dimensions[ 0 ] * dimensions[ 1 ] * dimensions[ 2 ] ];
		}

		final int[] offsets = new int[ dimensions[ 2 ] * dimensions[ 1 ] * dimensions[ 0 ] ];
		final LongMappedAccessData listData = LongMappedAccessData.factory.createStorage( 32 );
		final LabelMultisetEntryList list = new LabelMultisetEntryList( listData, 0 );
		final LabelMultisetEntry entry = new LabelMultisetEntry( 0, 1 );
		int nextListOffset = 0;
		final TLongIntHashMap idOffsetHash = new TLongIntHashMap(
				Constants.DEFAULT_CAPACITY,
				Constants.DEFAULT_LOAD_FACTOR,
				-1,
				-1);
A:		for ( int i = 0; i < data.length; ++i )
		{
			final long id = data[ i ] & 0xffff;

			final int offset = idOffsetHash.get( id );
			if ( offset == idOffsetHash.getNoEntryValue() )
			{
				list.createListAt( listData, nextListOffset );
				entry.setId( id );
				list.add( entry );
				offsets[ i ] = nextListOffset;
				idOffsetHash.put( id, nextListOffset );
				nextListOffset += list.getSizeInBytes();
			}
			else
			{
				offsets[ i ] = offset;
				continue A;
			}
		}
//		System.out.println( listData.size() );

		return new VolatileLabelMultisetArray( offsets, listData, true );
	}
}
