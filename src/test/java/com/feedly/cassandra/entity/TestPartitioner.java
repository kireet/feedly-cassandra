package com.feedly.cassandra.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.feedly.cassandra.IIndexRowPartitioner;

/*
 * partitions based on the first value of the index
 */
public class TestPartitioner implements IIndexRowPartitioner
{
    private static List<List<List<Object>>> _audit = new ArrayList<List<List<Object>>>();
    private static List<List<List<Object>>> _rangeAudit = new ArrayList<List<List<Object>>>();
    
    public static List<List<List<Object>>> partitionHistory()
    {
        return _audit;
    }

    public static List<List<List<Object>>> rangePartitionHistory()
    {
        return _rangeAudit;
    }
    
    @Override
    public List<List<Object>> partitionValue(List<Object> idxValue)
    {
        Long first = (Long) idxValue.get(0);
        List<List<Object>> rv = Collections.singletonList(Collections.<Object>singletonList(first/2)); 
        _audit.add(rv);
        return rv;
    }

    @Override
    public List<List<Object>> partitionRange(List<Object> startIdxValues, List<Object> endIdxValues)
    {
        Long start = (Long) startIdxValues.get(0);
        Long end = (Long) endIdxValues.get(0);
        
        List<List<Object>> rv = range(start, end);
        _rangeAudit.add(rv);
        
        return rv;
    }

    private List<List<Object>> range(Object start, Object end)
    {
        List<List<Object>> range = new ArrayList<List<Object>>();
        long first = ((Number) start).longValue();
        long last = ((Number) end).longValue();
        
        for(long c = first/2; c <= last/2; c++)
            range.add(Collections.<Object>singletonList(c));
        
        return range;
    }

}
