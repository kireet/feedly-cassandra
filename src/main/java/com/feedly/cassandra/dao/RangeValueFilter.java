package com.feedly.cassandra.dao;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

import com.feedly.cassandra.entity.EntityMetadata;
import com.feedly.cassandra.entity.IndexMetadata;
import com.feedly.cassandra.entity.PropertyMetadataBase;
import com.feedly.cassandra.entity.SimplePropertyMetadata;
import com.feedly.cassandra.entity.enhance.IEnhancedEntity;

/**
 * used to perform filter-on-read logic during index retrievals. This class handles between checks.
 * 
 * @author kireet
 *
 * @param <V> the entity type
 */
class RangeValueFilter<V> implements IValueFilter<V>
{
    private final EntityMetadata<V> _entityMeta;
    private final Map<PropertyMetadataBase, Object> _startProps = new HashMap<PropertyMetadataBase, Object>();
    private final Map<PropertyMetadataBase, Object> _endProps = new HashMap<PropertyMetadataBase, Object>();
    
    public RangeValueFilter(EntityMetadata<V> meta, V startTemplate, V endTemplate, IndexMetadata idx)
    {
        _entityMeta = meta;
        toMap(startTemplate, _startProps, idx);
        toMap(endTemplate, _endProps, idx);
    }

    private void toMap(V template, 
                       Map<PropertyMetadataBase, Object> props, 
                       IndexMetadata idx)
    {
        BitSet dirty = ((IEnhancedEntity)template).getModifiedFields();
        
        for(int i = dirty.nextSetBit (0); i>= 0; i = dirty.nextSetBit(i+1)) 
        {
            PropertyMetadataBase p = _entityMeta.getProperties().get(i);
            props.put(p, invokeGetter(p, template));
        }      
        
        for(SimplePropertyMetadata pm : idx.getIndexedProperties())
            props.remove(pm);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public EFilterResult isFiltered(IndexedValue<V> value)
    {
        /*
         * check current val is within range property by property
         */
        
        for(Map.Entry<PropertyMetadataBase, Object> entry : _startProps.entrySet())
        {
            Comparable tVal = (Comparable) entry.getValue();
            Comparable vVal = (Comparable) invokeGetter(entry.getKey(), value.getValue());
            
            if(tVal.compareTo(vVal) > 0)
                return EFilterResult.FAIL;
        }
        
        
        for(Map.Entry<PropertyMetadataBase, Object> entry : _endProps.entrySet())
        {
            Comparable tVal = (Comparable) entry.getValue();
            Comparable vVal = (Comparable) invokeGetter(entry.getKey(), value.getValue());
            
            if(tVal.compareTo(vVal) < 0)
                return EFilterResult.FAIL;
        }

        return EFilterResult.PASS;
    }
    
    protected Object invokeGetter(PropertyMetadataBase pm, V obj)
    {
        try
        {
            return pm.getGetter().invoke(obj);
        }
        catch(Exception e)
        {
            throw new IllegalArgumentException("unexpected error invoking " + pm.getGetter(), e);
        }
    }
}
