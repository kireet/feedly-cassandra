package com.feedly.cassandra.dao;

import java.nio.ByteBuffer;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import me.prettyprint.hector.api.beans.AbstractComposite.ComponentEquality;
import me.prettyprint.hector.api.beans.DynamicComposite;
import me.prettyprint.hector.api.beans.HColumn;
import me.prettyprint.hector.api.beans.Row;
import me.prettyprint.hector.api.beans.Rows;
import me.prettyprint.hector.api.factory.HFactory;
import me.prettyprint.hector.api.query.MultigetSliceQuery;
import me.prettyprint.hector.api.query.SliceQuery;

import org.apache.commons.lang.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feedly.cassandra.EConsistencyLevel;
import com.feedly.cassandra.IKeyspaceFactory;
import com.feedly.cassandra.entity.EntityMetadata;
import com.feedly.cassandra.entity.IndexMetadata;
import com.feedly.cassandra.entity.SimplePropertyMetadata;

/*
 * used to fetch data using custom secondary indexes. Lazy loading is supported.
 */
class RangeIndexFindHelper<K, V> extends LoadHelper<K, V>
{
    private static final Logger _logger = LoggerFactory.getLogger(RangeIndexFindHelper.class.getName());
    
    private final IndexedValueComparator<V> SORT_ASC = new IndexedValueComparator<V>(true);  
    private final IndexedValueComparator<V> SORT_DESC = new IndexedValueComparator<V>(false);  
    
    private final GetHelper<K, V> _getHelper;
    private final IStaleIndexValueStrategy _staleValueStrategy;
    RangeIndexFindHelper(EntityMetadata<V> meta, IKeyspaceFactory factory, IStaleIndexValueStrategy staleValueStrategy, int statsSize)
    {
        super(meta, factory, statsSize);
        _getHelper = new GetHelper<K, V>(meta, factory, statsSize);
        _staleValueStrategy = staleValueStrategy;
    }

    @Override
    public OperationStatistics stats()
    {
        return _getHelper.stats();
    }
    
    public OperationStatistics indexStats()
    {
        return _stats;
    }
    
    private V uniqueValue(Collection<V> values)
    {
        if(values == null || values.isEmpty())
            return null;
        
        if(values.size() > 1)
            throw new IllegalStateException("non-unique value");
        
        return values.iterator().next();
    }
    
    public V find(V template, FindOptions options, IndexMetadata index)
    {
        return uniqueValue(mfind(template, options, index));
    }
    

    public Collection<V> mfind(V template, FindOptions options, IndexMetadata index)
    {
        RangeIndexQueryResult<K> result = findKeys(template, template, EFindOrder.NONE, options.getMaxRows(), index, options.getConsistencyLevel());
        
        IValueFilter<V> filter = new EqualityValueFilter<V>(_entityMeta, template, index);
        
        _stats.incrNumOps(1);
        return new LazyLoadedCollection(result, filter, options, EFindOrder.NONE, index, options.getConsistencyLevel());
    }
    

    public Collection<V> mfindBetween(V startTemplate, V endTemplate, FindBetweenOptions options, IndexMetadata index)
    {
        RangeIndexQueryResult<K> result = findKeys(startTemplate, endTemplate, options.getRowOrder(), options.getMaxRows(), index, options.getConsistencyLevel());
        
        IValueFilter<V> f = new RangeValueFilter<V>(_entityMeta, startTemplate, endTemplate, index);

        _stats.incrNumOps(1);
        return new LazyLoadedCollection(result, f, options, options.getRowOrder(), index, options.getConsistencyLevel());
    }


    private List<IndexedValue<V>> filterValues(RangeIndexQueryResult<K> queryResult, 
                                               List<K> orderedKeys,
                                               List<V> values, 
                                               IValueFilter<V> filter, 
                                               IndexMetadata index,
                                               EConsistencyLevel level)
    {
        List<StaleIndexValue> filtered = null;
        List<IndexedValue<V>> rv = new ArrayList<IndexedValue<V>>();
        int excludedCnt = 0;
        Map<K, List<StaleIndexValue>> staleValues = queryResult.getCurrentValues();

        for(int i = values.size() - 1; i >= 0; i--)
        {
            K key = orderedKeys.get(i);
            V value = values.get(i);
            
            for(StaleIndexValue staleValue : staleValues.get(key))
            {
                if(value == null)
                {
                    if(filtered == null)
                        filtered = new ArrayList<StaleIndexValue>();
                    
                    _logger.trace("{} no value found", key);
                    filtered.add(staleValue);
                }
                else
                {
                    IndexedValue<V> idxValue = indexedValue(value, index);
                    
                    List<Object> idxPropVals = staleValue.getColumnName();
                    List<Object> rowPropVals = idxValue.getIndexValues();
                    
                    EFilterResult result = null;
                    
                    if(rowPropVals.size() != idxPropVals.size() - 1) //last value of index column is row key
                    {
                        result = EFilterResult.FAIL_STALE;
                        _logger.trace("{} index prop length mismatch {} != {} - 1", new Object[] {key, rowPropVals.size(), idxPropVals.size()});
                    }
                    else
                    {
                        for(int j = rowPropVals.size() - 1; j >= 0; j--)
                        {
                            Object idxPropVal = idxPropVals.get(j);
                            if(idxPropVal instanceof ByteBuffer)
                            {
                                idxPropVal = index.getIndexedProperties().get(j).getSerializer().fromByteBuffer((ByteBuffer) idxPropVal);
                            }
                            if(!idxPropVal.equals(rowPropVals.get(j)))
                            {
                                result = EFilterResult.FAIL_STALE;
                                _logger.trace("{} index prop val mismatch {} != {}", new Object[] {key, idxPropVal, rowPropVals.get(j)});
                                break;
                            }
                        }
                    }
                    
                    if(result == null)
                        result = filter.isFiltered(idxValue);
                    
                    if(result == EFilterResult.FAIL_STALE)
                    {
                        if(filtered == null)
                            filtered = new ArrayList<StaleIndexValue>();
                        
                        filtered.add(staleValue);
                    }
                    else if(result == EFilterResult.PASS)
                        rv.add(idxValue);
                    else
                        excludedCnt++;
                }
            }
        }
        
        if(filtered != null)
        {
            _getHelper.stats().incrNumRows(-excludedCnt);
            _staleValueStrategy.handle(_entityMeta, index, _keyspaceFactory.createKeyspace(level), filtered);
            
            _logger.debug("filtered {} stale values from index [{}]. {} excluded, retained {}", 
                          new Object[] { filtered.size(), index, excludedCnt, rv.size() });
        }
        else
            _logger.debug("no stale rows filtered from index [{}]. {} excluded. retained {}", 
                          new Object[] {index, excludedCnt, rv.size()});
        
        return rv;
    }

    private IndexedValue<V> indexedValue(V v, IndexMetadata index)
    {
        List<SimplePropertyMetadata> indexedProperties = index.getIndexedProperties();
        List<Object> indexValues = new ArrayList<Object>(indexedProperties.size());
        
        for(SimplePropertyMetadata pm : indexedProperties)
        {
            Object propVal = invokeGetter(pm, v);
            if(propVal == null)
                break;
            
            indexValues.add(propVal);
        }
        return new IndexedValue<V>(indexValues, v);
    }

    private List<Object> indexValues(V template, IndexMetadata index) 
    {
        BitSet dirty = asEntity(template).getModifiedFields();
        Set<SimplePropertyMetadata> modified = new HashSet<SimplePropertyMetadata>();
        List<Object> propValues = new ArrayList<Object>();
        for(int i = dirty.nextSetBit(0); i>= 0; i = dirty.nextSetBit(i+1))
            modified.add((SimplePropertyMetadata) _entityMeta.getProperties().get(i));
        
        for(SimplePropertyMetadata pm : index.getIndexedProperties())
        {
            if(modified.contains(pm))
                propValues.add(invokeGetter(pm, template));
            else
                break;
        }
        
        return propValues;
    }
    
    /*
     * index column family structure
     * row key:   idx_id:partition key 
     * column:    index value:rowkey
     * value:     meaningless
     */
    private RangeIndexQueryResult<K> findKeys(V startTemplate, V endTemplate, EFindOrder rowOrder, int maxKeys, IndexMetadata index, EConsistencyLevel level)
    {
        List<Object> startPropVals = indexValues(startTemplate, index);
        List<Object> endPropVals;
        List<List<Object>> indexPartitions;
        if(startTemplate == endTemplate)
        {
            endPropVals = startPropVals;
            indexPartitions = index.getIndexPartitioner().partitionValue(startPropVals);
        }
        else
        {
            endPropVals = indexValues(endTemplate, index);
            indexPartitions = index.getIndexPartitioner().partitionRange(startPropVals, endPropVals);            
        }
        
        _logger.trace("reading from partitions {}", indexPartitions);
        List<DynamicComposite> rowKeys = new ArrayList<DynamicComposite>();
        for(List<Object> partition : indexPartitions)
        {
            DynamicComposite rowKey = new DynamicComposite();
            rowKey.add(index.id());
            for(Object pval : partition)
                rowKey.add(pval);
            rowKeys.add(rowKey);
        }

        DynamicComposite startCol = new DynamicComposite(startPropVals);
        startCol.setEquality(ComponentEquality.EQUAL);
        DynamicComposite endCol = new DynamicComposite(endPropVals);
        endCol.setEquality(ComponentEquality.GREATER_THAN_EQUAL);
        
        return fetchInitialBatch(rowKeys.toArray(new DynamicComposite[rowKeys.size()]), startCol, endCol, rowOrder, index, level);
    }
    
    @SuppressWarnings("unchecked")
    private RangeIndexQueryResult<K> fetchInitialBatch(DynamicComposite[] partitionKeys, 
                                                       DynamicComposite startCol,
                                                       DynamicComposite endCol,
                                                       EFindOrder colOrder, 
                                                       IndexMetadata index,
                                                       EConsistencyLevel level)
    {
        RangeIndexQueryResult<K> rv = new RangeIndexQueryResult<K>();
        List<RangeIndexQueryPartitionResult> partitionResults = rv.getPartitionResults();
        
        if(colOrder == EFindOrder.NONE && partitionKeys.length <= CassandraDaoBase.COL_RANGE_SIZE) //can attempt to find all rows at once
        {
            long startTime = System.nanoTime();
            MultigetSliceQuery<DynamicComposite,DynamicComposite,byte[]> multiGetQuery =
                    HFactory.createMultigetSliceQuery(_keyspaceFactory.createKeyspace(level), SER_DYNAMIC_COMPOSITE, SER_DYNAMIC_COMPOSITE, SER_BYTES);
            
            multiGetQuery.setKeys(partitionKeys);
            multiGetQuery.setColumnFamily(_entityMeta.getIndexFamilyName());
            Rows<DynamicComposite,DynamicComposite,byte[]> indexRows;
            
            int colRangeSize = CassandraDaoBase.COL_RANGE_SIZE/partitionKeys.length;
            multiGetQuery.setRange(startCol, endCol, false, colRangeSize); //count here applies to the column slice of each row, not the overall count
            indexRows = multiGetQuery.execute().get();
            
            boolean hasMore = false;
            rv.setPartitionPos(partitionKeys.length - 1); //init to no additional results
            int partitionIdx = 0;
            for(Row<DynamicComposite, DynamicComposite, byte[]> row : indexRows)
            {
                RangeIndexQueryPartitionResult partitionResult = new RangeIndexQueryPartitionResult();
                partitionResult.setPartitionKey(row.getKey());

                List<HColumn<DynamicComposite,byte[]>> columns = row.getColumnSlice().getColumns();
                for(HColumn<DynamicComposite, byte[]> col : columns)
                {
                    K k = (K) col.getName().get(col.getName().size()-1);
                    StaleIndexValue v = new StaleIndexValue(row.getKey(), col.getName(), col.getClock());
                    rv.add(k, v);
                }

                if(columns.size() == colRangeSize)
                {
                    partitionResult.setHasMore(true);
                    if(!hasMore)
                    {
                        rv.setPartitionPos(partitionIdx);
                        hasMore = true;
                    }
                    
                    DynamicComposite start = columns.get(colRangeSize - 1).getName();
                    start.setEquality(ComponentEquality.GREATER_THAN_EQUAL);
                    partitionResult.setStartCol(start);
                    partitionResult.setEndCol(endCol);
                }
                else
                {
                    partitionResult.setStartCol(startCol);
                    partitionResult.setEndCol(endCol);
                    partitionResult.setHasMore(false);
                }
                
                partitionResults.add(partitionResult);
                partitionIdx++;
                
                if(!hasMore)
                    rv.setPartitionPos(partitionIdx);
            }
            
            _stats.addRecentTiming(System.nanoTime() - startTime);
            _stats.incrNumCassandraOps(1);
            _stats.incrNumCols(rv.getCurrentValues().size());
            _stats.incrNumRows(rv.getCurrentValues().size());
        }
        else 
        {
            /*
             * ordered results requested, need to proceed through the partitions in order, this means a multi get cannot be done...
             */
            
            if(colOrder == EFindOrder.DESCENDING)
            {
                partitionKeys = partitionKeys.clone();
                ArrayUtils.reverse(partitionKeys);
            }
            
            boolean foundResults = false;
            
            /*
             * find the first partition with some results and fetch them, set the remaining partitions up for a later fetch
             */
            for(int i = 0; i < partitionKeys.length; i++)
            {
                if(!foundResults)
                {
                    RangeIndexQueryPartitionResult pr = new RangeIndexQueryPartitionResult();
                    pr.setPartitionKey(partitionKeys[i]);
                    pr.setHasMore(true);
                    pr.setStartCol(startCol);
                    pr.setEndCol(endCol);
                    
                    int fetchCnt = fetchFromPartition(rv, pr, colOrder, 0, level);
                    foundResults = fetchCnt > 0;
                    
                    if(foundResults)
                        partitionResults.add(pr);
                }
                else
                {
                    RangeIndexQueryPartitionResult partitionResult = new RangeIndexQueryPartitionResult();
                    partitionResult.setPartitionKey(partitionKeys[i]);
                    partitionResult.setHasMore(true);
                    partitionResult.setStartCol(startCol);
                    partitionResult.setEndCol(endCol);
                    partitionResults.add(partitionResult);
                }
            }
        }

        _logger.debug("initial fetch: index [{}] {} - {}, found {} keys", new Object[]{ index, startCol, endCol, rv.getCurrentValues().size() });  

        return rv;
    }
    
    private void fetchBatch(RangeIndexQueryResult<K> result, int maxRows, EFindOrder order, IndexMetadata index, EConsistencyLevel level)
    {
        if(!result.hasMore())
            return;
        
        result.clearCurrent();

        int numPartitions = result.getPartitionResults().size();
        List<RangeIndexQueryPartitionResult> partitionResults = result.getPartitionResults();
        
        int fetchCnt = 0;
        for(int i = result.getPartitionPos(); i < numPartitions; i++)
        {
            RangeIndexQueryPartitionResult p = partitionResults.get(i);
            if(p.hasMore())
            {
                fetchCnt = fetchFromPartition(result, p, order, i, level);
                
                if(fetchCnt > 0)
                    break;
            }
        }
        
        if(fetchCnt == 0)
        {
            //no results
            result.setPartitionPos(numPartitions);
            result.setCurrentValues(Collections.<K, List<StaleIndexValue>>emptyMap());
        }

    }



    private List<HColumn<DynamicComposite, byte[]>> executeSliceQuery(DynamicComposite partitionKey,
                                                                      DynamicComposite startCol,
                                                                      DynamicComposite endCol,
                                                                      EFindOrder colOrder,
                                                                      EConsistencyLevel level)
    {
        SliceQuery<DynamicComposite,DynamicComposite,byte[]> query =
                HFactory.createSliceQuery(_keyspaceFactory.createKeyspace(level), SER_DYNAMIC_COMPOSITE, SER_DYNAMIC_COMPOSITE, SER_BYTES);
        
        query.setKey(partitionKey);
        query.setColumnFamily(_entityMeta.getIndexFamilyName());
        
        if(colOrder == EFindOrder.DESCENDING)
            query.setRange(endCol, startCol, true, CassandraDaoBase.COL_RANGE_SIZE);
        else
            query.setRange(startCol, endCol, false, CassandraDaoBase.COL_RANGE_SIZE);
            
        List<HColumn<DynamicComposite,byte[]>> columns = query.execute().get().getColumns();
        return columns;
    }
    
    @SuppressWarnings("unchecked")
    private int fetchFromPartition(RangeIndexQueryResult<K> result,
                                   RangeIndexQueryPartitionResult p, 
                                   EFindOrder order,
                                   int pos,
                                   EConsistencyLevel level)
    {
        long startTime = System.nanoTime();
        List<HColumn<DynamicComposite,byte[]>> columns = executeSliceQuery(p.getPartitionKey(), 
                                                                           p.getStartCol(), 
                                                                           p.getEndCol(), 
                                                                           order,
                                                                           level); 
    
        int size = columns.size();
        if(size > 0)
        {
            for(HColumn<DynamicComposite, byte[]> col : columns)
            {
                K k = (K) col.getName().get(col.getName().size()-1);
                StaleIndexValue v = new StaleIndexValue(p.getPartitionKey(), col.getName(), col.getClock());
                result.add(k, v);
            }


            if(order == EFindOrder.DESCENDING)
            {
                DynamicComposite end = columns.get(columns.size() - 1).getName();
                end.setEquality(ComponentEquality.LESS_THAN_EQUAL);
                p.setEndCol(end);
            }
            else
            {
                DynamicComposite start = columns.get(columns.size() - 1).getName();
                start.setEquality(ComponentEquality.GREATER_THAN_EQUAL);
                p.setStartCol(start);
            }

            boolean hasMore = size == CassandraDaoBase.COL_RANGE_SIZE;

            _logger.debug("fetched {} keys from partition[{}] ({}), has more == {}", 
                         new Object[] {size, pos, p.getPartitionKey(), hasMore});

            p.setHasMore(hasMore);
            result.setPartitionPos(hasMore ? pos : pos+1);
        }
    
        _stats.addRecentTiming(System.nanoTime() - startTime);
        _stats.incrNumCassandraOps(1);
        _stats.incrNumCols(size);
        _stats.incrNumRows(size);

        return size;
    }

    private List<V> toRows(RangeIndexQueryResult<K> result, 
                           FindOptions options, 
                           EFindOrder order, 
                           IValueFilter<V> filter, 
                           IndexMetadata index,
                           EConsistencyLevel level)
    {
        if(result.getCurrentKeys().isEmpty())
        {
            return Collections.emptyList();
        }
        
        if(options.getColumnFilterStrategy() == EColumnFilterStrategy.INCLUDES)
        {
            Set<Object> partialProperties = new HashSet<Object>(partialProperties(options.getIncludes(), options.getExcludes()));
            for(SimplePropertyMetadata pm : index.getIndexedProperties())
                partialProperties.add(pm.getName());
            
            try
            {
                options = (FindOptions) options.clone();
                options.setIncludes(partialProperties);
                options.setExcludes(null);
            }
            catch(CloneNotSupportedException ex)
            {
                throw new RuntimeException(ex);
            }
        }

        List<K> currentKeys = new ArrayList<K>(result.getCurrentKeys());
        List<V> rows = _getHelper.mget(currentKeys, null, options);
        
        List<IndexedValue<V>> values = filterValues(result, currentKeys, rows, filter, index, level);
        rows.clear();
        
        if(!values.isEmpty())
        {
            if(order == EFindOrder.ASCENDING)
                Collections.sort(values, SORT_ASC);
            else if(order == EFindOrder.DESCENDING)
                Collections.sort(values, SORT_DESC);

            for(IndexedValue<V> v : values)
                rows.add(v.getValue());
        }
        
        return rows;
    }
    
    private class LazyLoadedIterator implements Iterator<V>
    {
        private int _remRows; //remaining rows left to fetch, based on max set by user and if the last batch fetched was maximal
        private List<V> _current;
        private Iterator<V> _currentIter;
        private V _next;
        private int _iteratedCnt = 0;
        private final FindOptions _options;
        private final RangeIndexQueryResult<K> _result;
        private final EFindOrder _order;
        private final IndexMetadata _index;
        private final IValueFilter<V> _filter;
        private final LazyLoadedCollection _parent;
        private final EConsistencyLevel _level;
        
        @SuppressWarnings("unchecked")
        public LazyLoadedIterator(LazyLoadedCollection parent, 
                                  List<V> first,
                                  RangeIndexQueryResult<K> result,
                                  IValueFilter<V> filter,
                                  FindOptions options,
                                  EFindOrder order,
                                  IndexMetadata index, 
                                  EConsistencyLevel level)
        {
            _parent = parent;
            _current = first;
            _currentIter = first.iterator();
            _next = _currentIter.next();
            _filter = filter;
            _options = options;
            _order = order;
            _index = index;
            _level = level;
            try
            {
                _result = (RangeIndexQueryResult<K>) result.clone();
            }
            catch(CloneNotSupportedException ex)
            {
                throw new RuntimeException(ex);//should never happen
            }
            
            if(_result.hasMore())
                _remRows = options.getMaxRows() - first.size();
            else
                _remRows = 0;
        }


        @Override
        public boolean hasNext()
        {
            return _next != null;
        }

        @Override
        public V next()
        {
            if(_next == null)
                throw new NoSuchElementException();
            
            V rv = _next;
            
            if(_currentIter.hasNext())
                _next = _currentIter.next();
            else if(_remRows == 0)
            {
                _next = null;
            }
            else //fetch next batch
            {
                List<V> rows = Collections.emptyList();
                while(rows.isEmpty() && _result.hasMore())
                {
                    fetchBatch(_result, _options.getMaxRows(), _order, _index, _level);
                    rows = toRows(_result, _options, _order, _filter, _index, _level);
                }
                
                if(rows.size() >= _remRows)
                {
                    if(rows.size() > _remRows) //trim
                    {
                        rows = rows.subList(0, _remRows);
                        rows = new ArrayList<V>(rows); //detach from original list
                    }
                }
                
                _remRows = Math.max(0, _remRows - rows.size());
                
                _current = rows;
                
                if(_current.isEmpty())
                {
                    _current = null;
                    _currentIter = null;
                    _next = null;
                }
                else
                {
                    _currentIter = _current.iterator();
                    _next = _currentIter.next();
                }
            }
            
            _iteratedCnt++;
            if(_next == null)
                _parent.setSize(_iteratedCnt); //we have a row count, notify parent so subsequent calls to size() don't have to fetch all rows
            
            return rv;
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException();
        }
    }
    
    
    private class LazyLoadedCollection extends AbstractCollection<V>
    {
        private final RangeIndexQueryResult<K> _result;
        private final IValueFilter<V> _filter;
        private final FindOptions _options;
        private final EFindOrder _order;
        private final IndexMetadata _index;
        private List<V> _all = null; //if it is known all rows have been fetched, this field is set
        private List<V> _first;
        private int _size = -1;
        private final EConsistencyLevel _level;
        
        public LazyLoadedCollection(RangeIndexQueryResult<K> result,
                                    IValueFilter<V> filter,
                                    FindOptions options,
                                    EFindOrder order,
                                    IndexMetadata index,
                                    EConsistencyLevel level)
        {
            _result = result;
            _filter = filter;
            _options = options;
            _order = order;
            _index = index;
            _level = level;
            
            int maxRows = _options.getMaxRows();
            
            List<V> rows = toRows(result, options, order, filter, index, level);

            while(rows.isEmpty() && result.hasMore())
            {
                fetchBatch(result, options.getMaxRows(), order, index, _level);
                rows = toRows(result, options, order, filter, index, level);
            }
            
            if(!result.hasMore() || result.getCurrentValues().size() >= maxRows)
            {
                if(rows.size() >= maxRows)
                {
                    if(rows.size() > maxRows) //trim
                    {
                        rows = rows.subList(0, maxRows);
                        rows = new ArrayList<V>(rows); //detach from original list
                    }
                }
                
                _all = rows;
                _first = rows;
            }
            else
                _first = rows; //will need to iterate...
            
        }

        //override, don't want to invoke size, just to check if empty
        @Override
        public boolean isEmpty()
        {
            return _first.isEmpty();
        }
        
        //can aggressively fetch and retain all values, use with caution
        @Override
        public int size()
        {
            if(_size >= 0)
                return _size;
            if(_all == null)
            {
                Iterator<V> iter = iterator();
                _all = new ArrayList<V>();
                while(iter.hasNext())
                    _all.add(iter.next());
            }
            
            return _all.size();
        }
        
        @Override
        public java.util.Iterator<V> iterator()
        {
            if(_all != null)
                return _all.iterator();
            
            return new LazyLoadedIterator(this, _first, _result, _filter, _options, _order, _index, _level);
        }
        
        void setSize(int size)
        {
            _size = size;
        }
    }

}
