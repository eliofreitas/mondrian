/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2002-2005 Julian Hyde
// Copyright (C) 2005-2013 Pentaho and others
// All Rights Reserved.
*/
package mondrian.rolap.agg;

import mondrian.pref.*;
import mondrian.rolap.*;
import mondrian.spi.Dialect;
import mondrian.spi.SegmentBody;
import mondrian.spi.SegmentColumn;
import mondrian.spi.SegmentHeader;
import mondrian.test.PerformanceTest;
import mondrian.util.ByteString;
import mondrian.util.Pair;

import java.util.*;

/**
 * <p>Test for <code>SegmenBuilder</code></p>
 *
 * @author mcampbell
 */
public class SegmentBuilderTest extends BatchTestCase {

    protected void setUp() throws Exception {
        super.setUp();
        PrefDef.EnableInMemoryRollup.with(propSaver).set(true);
        PrefDef.EnableNativeNonEmpty.with(propSaver).set(true);
        PrefDef.SparseSegmentDensityThreshold.with(propSaver).set(.5);
        PrefDef.SparseSegmentCountThreshold.with(propSaver).set(1000);
    }

    public void _testSparseRollup() {
        // functional test for a case that causes OOM if rollup creates
        // a dense segment.
        // Leaving disabled pending removal of HighCardSqlTupleReader.
        // Currently this query will never complete due to an issue w/
        // HCSTR

        if (PerformanceTest.LOGGER.isDebugEnabled()) {
            // load the cache with a segment for the subsequent rollup
            executeQuery(
                "select NON EMPTY Crossjoin([Store].[Store Type].[Store Type].members, "
                + "CrossJoin([Promotion].[Media Type].[Media Type].members, "
                + " Crossjoin([Promotion].[Promotions].[Promotion Name].members, "
                + "Crossjoin([Store].[Stores].[Store Name].Members, "
                + "Crossjoin( [product].[products].[product name].members, "
                + "Crossjoin( [Customer].[Customers].[Name].members,  "
                + "[Customer].[Gender].[Gender].members)))))) on 1, "
                + "{ measures.[unit sales] } on 0 from [Sales]");

            executeQuery(
                "select NON EMPTY Crossjoin([Store].[Store Type].[Store Type].members, "
                + "CrossJoin([Promotion].[Media Type].[Media Type].members, "
                + " Crossjoin([Promotion].[Promotions].[Promotion Name].members, "
                + "Crossjoin([Store].[Stores].[Store Name].Members, "
                + "Crossjoin( [product].[products].[product name].members, "
                + " [Customer].[Customers].[Name].members))))) on 1, "
                + "{ measures.[unit sales] } on 0 from [Sales]");

            // second query will throw OOM if .rollup() attempts
            // to create a dense segment
        }
    }

    public void testRollupWithIntOverflowPossibility() {
        // rolling up a segment that would cause int overflow if
        // rolled up to a dense segment
        // MONDRIAN-1377

        // make a source segment w/ 3 cols, 47K vals each,
        // target segment has 2 of the 3 cols.
        // count of possible values will exceed Integer.MAX_VALUE
        Pair<SegmentHeader, SegmentBody> rollup =
            SegmentBuilder.rollup(
                makeSegmentMap(
                    new String[] {"col1", "col2", "col3"}, 47000, 4),
                new HashSet<String>(Arrays.asList("col1", "col2")),
                null, RolapAggregator.Sum, Dialect.Datatype.Numeric);
        assertTrue(rollup.right instanceof  SparseSegmentBody);
    }

    public void testRollupWithOOMPossibility() {
        // rolling up a segment that would cause OOM if
        // rolled up to a dense segment
        // MONDRIAN-1377

        // make a source segment w/ 3 cols, 44K vals each,
        // target segment has 2 of the 3 cols.
        Pair<SegmentHeader, SegmentBody> rollup =
            SegmentBuilder.rollup(
                makeSegmentMap(
                    new String[] {"col1", "col2", "col3"}, 44000, 4),
                new HashSet<String>(Arrays.asList("col1", "col2")),
                null, RolapAggregator.Sum, Dialect.Datatype.Numeric);
        assertTrue(rollup.right instanceof  SparseSegmentBody);
    }

    public void testRollupShouldBeDense() {
        // Fewer than 1000 column values in rolled up segment.
        Pair<SegmentHeader, SegmentBody> rollup =
            SegmentBuilder.rollup(
                makeSegmentMap(
                    new String[] {"col1", "col2", "col3"}, 10, 15),
                new HashSet<String>(Arrays.asList("col1", "col2")),
                null, RolapAggregator.Sum, Dialect.Datatype.Numeric);
        assertTrue(rollup.right instanceof DenseDoubleSegmentBody);

        // greater than 1K col vals, above density ratio
        rollup =
            SegmentBuilder.rollup(
                makeSegmentMap(
                    new String[] {"col1", "col2", "col3", "col4"},
                    11, 10000),   // 1331 possible intersections (11*3)
                new HashSet<String>(Arrays.asList("col1", "col2", "col3")),
                null, RolapAggregator.Sum, Dialect.Datatype.Numeric);
        assertTrue(rollup.right instanceof DenseDoubleSegmentBody);
    }

    /**
     * Creates a rough segment map for testing purposes, containing
     * the array of column names passed in, with numValsPerCol dummy
     * values placed per column.  Populates the cells in the body with
     * numPopulatedCells dummy values placed in the first N places of the
     * values array.
     */
    private Map<SegmentHeader, SegmentBody> makeSegmentMap(
        String[] colNames, int numValsPerCol, int numPopulatedCells)
    {
        Pair<SegmentHeader, SegmentBody> headerBody = makeDummyHeaderBodyPair(
            colNames,
            dummyColumnValues(
                colNames.length, numValsPerCol), numPopulatedCells);
        Map<SegmentHeader, SegmentBody> map =
            new HashMap<SegmentHeader, SegmentBody>();
        map.put(headerBody.left, headerBody.right);

        return map;
    }
    private Pair<SegmentHeader, SegmentBody> makeDummyHeaderBodyPair(
        String[] colExps, String[][] colVals, int numCellVals)
    {
        final Map<CellKey, Object> data =
            new HashMap<CellKey, Object>();

        final List<SegmentColumn> constrainedColumns =
            new ArrayList<SegmentColumn>();

        final List<Pair<SortedSet<Comparable>, Boolean>> axes =
            new ArrayList<Pair<SortedSet<Comparable>, Boolean>>();
        for (int i = 0; i < colVals.length; i++) {
            String colExp = colExps[i];
            SortedSet<Comparable> vals = new TreeSet<Comparable>();
            for (int j = 0; j < colVals[i].length; j++) {
                vals.add(new String(colVals[i][j]));
            }
            constrainedColumns.add(
                new SegmentColumn(
                    colExp,
                    colVals[i].length,
                    vals));
            axes.add(new Pair<SortedSet<Comparable>,
                Boolean>(vals, Boolean.FALSE));
        }

        Object [] cells = new Object[numCellVals];
        for (int i = 0; i < numCellVals; i++) {
            cells[i] = 123.123; // assign a non-null val
        }
        return new Pair<SegmentHeader, SegmentBody> (
            new SegmentHeader(
                "dummySchemaName",
                new ByteString(new byte[] {}),
                "dummyCubeName",
                "dummyMeasureName",
                constrainedColumns,
                Collections.<String>emptyList(),
                "dummyFactTable",
                null,
                Collections.<SegmentColumn>emptyList()),
            new DenseObjectSegmentBody(
                cells,
                axes));
    }

    private String [][] dummyColumnValues(int cols, int numVals) {
        String [][] dummyColVals = new String[cols][numVals];
        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < numVals; j++) {
                dummyColVals[i][j] = "c" + i + "v" + j;
            }
        }
        return dummyColVals;
    }
}
// End SegmentBuilderTest.java