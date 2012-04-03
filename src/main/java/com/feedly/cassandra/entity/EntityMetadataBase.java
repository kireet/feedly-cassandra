package com.feedly.cassandra.entity;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import me.prettyprint.hector.api.Serializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feedly.cassandra.anno.Column;

/**
 * This class holds metadata for a given entity including key, property and index information.
 * 
 * @author kireet
 * 
 * @param <V> the entity type
 */
public class EntityMetadataBase<V>
{
    private static final Logger _logger = LoggerFactory.getLogger(EntityMetadataBase.class.getName());
    
    private final Map<String, PropertyMetadataBase> _propsByName, _propsByPhysicalName;
    private final List<PropertyMetadataBase> _props;
    private final Map<String, Set<PropertyMetadataBase>> _propsByAnno;
    private final Class<V> _clazz;
    private final Map<PropertyMetadataBase, Integer> _propPositions;
    private final boolean _useCompositeColumns;

    @SuppressWarnings("unchecked")
    public EntityMetadataBase(Class<V> clazz, boolean useCompositeColumns)
    {
        _clazz = clazz;
        _useCompositeColumns = useCompositeColumns;
        
        Map<String, PropertyMetadataBase> props = new TreeMap<String, PropertyMetadataBase>(); 
        Map<String, PropertyMetadataBase>  propsByPhysical = new TreeMap<String, PropertyMetadataBase>();
        Map<String, Set<PropertyMetadataBase>> propsByAnno = new TreeMap<String, Set<PropertyMetadataBase>>();
        
        for(Field f : clazz.getDeclaredFields())
        {
            Method getter = getGetter(f);
            Method setter = getSetter(f);

            if(f.isAnnotationPresent(Column.class))
            {
                if(getter == null || setter == null)
                    throw new IllegalArgumentException("@Column field must have valid getter and setter.");

                Column anno = f.getAnnotation(Column.class);
                String col = anno.col();
                if(col.equals(""))
                    col = f.getName();
                
                if(anno.hashIndexed() && anno.rangeIndexed())
                    throw new IllegalStateException(f.getName() + ": property can be range or hash indexed, not both");
                
                PropertyMetadataBase pm = PropertyMetadataFactory.buildPropertyMetadata(f, col, getter, setter, (Class<? extends Serializer<?>>) anno.serializer(), useCompositeColumns);
                props.put(f.getName(), pm);
                if(propsByPhysical.put(col, pm) != null)
                    throw new IllegalStateException(f.getName() + ": physical column name must be unique - " + col);

                for(Annotation a : f.getDeclaredAnnotations())
                {
                    Set<PropertyMetadataBase> annos = propsByAnno.get(a.annotationType().getName());
                    if(annos == null)
                    {
                        annos = new TreeSet<PropertyMetadataBase>();
                        propsByAnno.put(a.annotationType().getName(),  annos);
                    }
                    annos.add(pm);
                }
            }
        }

        for(Entry<String, Set<PropertyMetadataBase>> annos : propsByAnno.entrySet())
            annos.setValue(Collections.unmodifiableSet(annos.getValue()));
        
        _propsByAnno = Collections.unmodifiableMap(propsByAnno);
        _propsByName = Collections.unmodifiableMap(props);
        _propsByPhysicalName = Collections.unmodifiableMap(propsByPhysical);

        List<PropertyMetadataBase> sorted = new ArrayList<PropertyMetadataBase>(props.values());
        Collections.sort(sorted);
        _props = Collections.unmodifiableList(sorted);
        
        Map<PropertyMetadataBase, Integer> positions = new HashMap<PropertyMetadataBase, Integer>();
        for(int i = sorted.size() - 1; i >=0; i--)
            positions.put(sorted.get(i), i);
        
        _propPositions = Collections.unmodifiableMap(positions);
    }

    protected Method getSetter(Field prop)
    {
        String name = "set" + Character.toUpperCase(prop.getName().charAt(0)) + prop.getName().substring(1);
        Method setter = null;

        try
        {
            setter = _clazz.getMethod(name, prop.getType());
            
            if(!EntityUtils.isValidSetter(setter))
                return null;
            
            return setter;
        }
        catch(NoSuchMethodException ex)
        {
            if(!prop.getName().startsWith("__"))
                _logger.trace(prop.getName() + " no setter {} ({}). excluding", name, prop.getType().getSimpleName());
            
            return null;
        }

    }
    
    protected Method getGetter(Field prop)
    {
        String name = "get" + Character.toUpperCase(prop.getName().charAt(0)) + prop.getName().substring(1);
        Method getter = null;

        try
        {
            getter = _clazz.getMethod(name);
            
            if(!getter.getReturnType().equals(prop.getType()) || !EntityUtils.isValidGetter(getter))
                return null;
            
            return getter;
        }
        catch(NoSuchMethodException ex)
        {
            if(!prop.getName().startsWith("__"))
                _logger.trace(prop.getName() + "no getter {}({}). excluding", name, prop.getType().getSimpleName());
            
            return null;
        }
    }
    
    public final PropertyMetadataBase getProperty(String name)
    {
        return _propsByName.get(name);
    }

    public final int getPropertyPosition(SimplePropertyMetadata pm)
    {
        return _propPositions.get(pm);
    }
    
    public final PropertyMetadataBase getPropertyByPhysicalName(String pname)
    {
        return _propsByPhysicalName.get(pname);
    }
    
    public final List<PropertyMetadataBase> getProperties()
    {
        return _props;
    }
  
    public final Set<PropertyMetadataBase> getAnnotatedProperties(Class<? extends Annotation> annoType)
    {
        Set<PropertyMetadataBase> rv = _propsByAnno.get(annoType.getName());
        
        return rv != null ? rv : Collections.<PropertyMetadataBase>emptySet();
    }
    
    public final Class<V> getType()
    {
        return _clazz;
    }
    
    public boolean useCompositeColumns()
    {
        return _useCompositeColumns;
    }
    
    @Override
    public final int hashCode()
    {
        return _clazz.hashCode();
    }
    
    @Override
    public final boolean equals(Object obj)
    {
        if(obj instanceof EntityMetadataBase<?>)
            return _clazz.equals(((EntityMetadataBase<?>) obj)._clazz);
            
        return false;
    }
    
    @Override
    public final String toString()
    {
        StringBuilder b = new StringBuilder();
        
        b.append(_clazz.getSimpleName());
        
        for(PropertyMetadataBase pm : _props)
            b.append("\n\t+ ").append(pm);
        
        return b.toString();
    }
    
}