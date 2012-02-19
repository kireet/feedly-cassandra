package com.feedly.cassandra;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import me.prettyprint.cassandra.model.BasicColumnDefinition;
import me.prettyprint.cassandra.model.BasicColumnFamilyDefinition;
import me.prettyprint.cassandra.serializers.StringSerializer;
import me.prettyprint.cassandra.service.CassandraHostConfigurator;
import me.prettyprint.cassandra.service.ThriftKsDef;
import me.prettyprint.hector.api.Cluster;
import me.prettyprint.hector.api.Keyspace;
import me.prettyprint.hector.api.beans.DynamicComposite;
import me.prettyprint.hector.api.ddl.ColumnDefinition;
import me.prettyprint.hector.api.ddl.ColumnFamilyDefinition;
import me.prettyprint.hector.api.ddl.ColumnIndexType;
import me.prettyprint.hector.api.ddl.ComparatorType;
import me.prettyprint.hector.api.ddl.KeyspaceDefinition;
import me.prettyprint.hector.api.factory.HFactory;

import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.io.compress.CompressionParameters;
import org.apache.cassandra.io.compress.DeflateCompressor;
import org.apache.cassandra.io.compress.SnappyCompressor;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feedly.cassandra.anno.ColumnFamily;
import com.feedly.cassandra.entity.EntityMetadata;
import com.feedly.cassandra.entity.PropertyMetadata;
import com.feedly.cassandra.entity.enhance.ColumnFamilyTransformTask;
import com.feedly.cassandra.entity.enhance.IEnhancedEntity;

public class PersistenceManager implements IKeyspaceFactory
{
    private static final Logger _logger = LoggerFactory.getLogger(PersistenceManager.class.getName());
    
    private boolean _syncSchema = true;
    private String[] _sourcePackages = new String[0];
    private Set<Class<?>> _colFamilies;
    private String _keyspace;
    private CassandraHostConfigurator _hostConfig;
    private String _clusterName;
    private Cluster _cluster;
    private int _replicationFactor = 1;
    
    public void setReplicationFactor(int r)
    {
        _replicationFactor = r;
    }
    
    public void setClusterName(String cluster)
    {
        _clusterName = cluster;
    }
    public void setKeyspace(String keyspace)
    {
        _keyspace = keyspace;
    }
    
    public void setHostConfiguration(CassandraHostConfigurator config)
    {
        _hostConfig = config;
    }
    
    public void setPackagePrefixes(String[] packages)
    {
        _sourcePackages = packages;
    }
    
    public void setSyncSchema(boolean b)
    {
        _syncSchema = b;
    }
    
    public void init()
    {
        if(_sourcePackages.length == 0)
            _logger.warn("No source packages configured! This is probably not right.");

        Set<Class<?>> annotated = new HashSet<Class<?>>();
        for(String pkg : _sourcePackages)
        {
            Reflections reflections = new Reflections(pkg);
            annotated.addAll(reflections.getTypesAnnotatedWith(ColumnFamily.class));
        }
        _logger.info("found {} classes", annotated.size());
        Iterator<Class<?>> iter = annotated.iterator();
        
        while(iter.hasNext())
        {
            Class<?> family = iter.next();
            boolean enh = false;
            for(Class<?> iface : family.getInterfaces())
            {
                if(iface.equals(IEnhancedEntity.class))
                {
                    enh = true;
                    break;
                }
            }
            
            if(!enh)
            {
                _logger.warn(family.getName() + " has not been enhanced after compilation, it will be ignored. See ", 
                             ColumnFamilyTransformTask.class.getName());
                
                iter.remove();
            }
        }

        _colFamilies = Collections.unmodifiableSet(annotated);
        
        _cluster = HFactory.getOrCreateCluster(_clusterName, _hostConfig);
        if(_syncSchema)
            syncKeyspace();
    }
    
    private void syncKeyspace()
    {
        KeyspaceDefinition kdef = _cluster.describeKeyspace(_keyspace);
        if(kdef == null)
        {
            kdef = HFactory.createKeyspaceDefinition(_keyspace,
                                                     ThriftKsDef.DEF_STRATEGY_CLASS,
                                                     _replicationFactor,
                                                     new ArrayList<ColumnFamilyDefinition>());
            
            _cluster.addKeyspace(kdef, true);
        }
        
        for(Class<?> family : _colFamilies)
        {
            syncColumnFamily(family, kdef);
        }
    }
    
    public Keyspace createKeyspace()
    {
        return HFactory.createKeyspace(_keyspace, _cluster);
    }
    
    
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void syncColumnFamily(Class<?> family, KeyspaceDefinition keyspaceDef)
    {
        ColumnFamily annotation = family.getAnnotation(ColumnFamily.class);
        EntityMetadata<?> meta = new EntityMetadata(family, annotation.name(), annotation.forceCompositeColumns());
        
        String familyName = annotation.name();
        ColumnFamilyDefinition existing = null;
        
        for(ColumnFamilyDefinition cfdef : keyspaceDef.getCfDefs())
        {
            if(cfdef.getName().equals(familyName))
            {
                _logger.debug("Column Family {} already exists", familyName);
                existing = cfdef;
                break;
            }
        }
        
        if(existing == null)
        {
            _logger.info("Column Family {} missing, creating...", familyName);

            ColumnFamilyDefinition cfDef = new BasicColumnFamilyDefinition(HFactory.createColumnFamilyDefinition(_keyspace, annotation.name()));
            if(meta.useCompositeColumns())
            {
                _logger.info("{}: comparator type: dynamic composite", familyName);
                cfDef.setComparatorType(ComparatorType.DYNAMICCOMPOSITETYPE);
                cfDef.setComparatorTypeAlias(DynamicComposite.DEFAULT_DYNAMIC_COMPOSITE_ALIASES);
            }
            else
            {
                _logger.info("{}: comparator type: UTF8", familyName);
                cfDef.setComparatorType(ComparatorType.UTF8TYPE);
            }
            
            Set<String> hashIndexed = new HashSet<String>();
            Set<String> rangeIndexed = new HashSet<String>();
            for(PropertyMetadata pm : meta.getProperties())
            {
                if(pm.isHashIndexed())
                {
                    cfDef.addColumnDefinition(createColDef(meta, familyName, pm));
                    hashIndexed.add(pm.getPhysicalName());
                }

                syncRangeIndexTables(familyName, pm, keyspaceDef);
                if(pm.isRangeIndexed())
                    rangeIndexed.add(pm.getPhysicalName());
            }
            
            syncRangeIndexWalTable(familyName, !rangeIndexed.isEmpty(), keyspaceDef);
            
            cfDef.setCompressionOptions(compressionOptions(annotation));
            _logger.info("{}: compression options: {}, hash indexed columns: {}, range indexed columns", 
                         new Object[] {familyName, cfDef.getCompressionOptions(), hashIndexed, rangeIndexed});
            
                
            _cluster.addColumnFamily(cfDef, true);
        }
        else 
        {
            existing = new BasicColumnFamilyDefinition(existing);
            Map<String, String> compressionOptions = existing.getCompressionOptions();
            boolean doUpdate = false;
            boolean hasRangeIndexes = false;
            
            Set<PropertyMetadata> missing = new HashSet<PropertyMetadata>(meta.getProperties());
            //check if existing column metadata is in sync
            for(ColumnDefinition colMeta : existing.getColumnMetadata())
            {
                PropertyMetadata pm = meta.getPropertyByPhysicalName(StringSerializer.get().fromByteBuffer(colMeta.getName()));
                if(pm != null)
                {
                    if(colMeta.getIndexType() != null && !pm.isHashIndexed())
                        _logger.warn("{}.{} is indexed in cassandra, but not in the data model. manual intervention needed", 
                                     familyName, pm.getPhysicalName());
                    
                    if(colMeta.getIndexType() == null && pm.isHashIndexed())
                        throw new IllegalStateException(familyName + "." + pm.getPhysicalName() + 
                                " is not indexed in cassandra, manually add the index and then restart");
                    
                    syncRangeIndexTables(familyName, pm, keyspaceDef);
                    if(pm.isRangeIndexed())
                        hasRangeIndexes = true;
                    
                    missing.remove(pm);
                }
                else
                {
                    _logger.warn("encountered unmapped column {}.{}", familyName, colMeta.getName());
                }
            }

            syncRangeIndexWalTable(familyName, hasRangeIndexes, keyspaceDef);
            
            for(PropertyMetadata pm : missing)
            {
                if(pm.isHashIndexed())
                {
                    existing.addColumnDefinition(createColDef(meta, familyName, pm));
                    
                    _logger.info("adding index on {}.{}", familyName, pm.getPhysicalName()); 
                    doUpdate = true;
                }
            }
            
            if(!annotation.compressed())
            {
                //if don't want to compress but family is compressed, disable compression
                if(compressionOptions != null && !compressionOptions.isEmpty())
                {
                    doUpdate = true;
                    existing.setCompressionOptions(null);
                }
            }
            else //compression requested, check that options are in sync
            {
                Map<String, String> newOpts = compressionOptions(annotation);
                
                if(!newOpts.equals(compressionOptions))
                {
                    doUpdate = true;
                    existing.setCompressionOptions(newOpts);
                }
            }
            
            if(doUpdate)
            {
                _logger.info("Updating compression options for family {}: {} ", familyName, existing.getCompressionOptions());
                _cluster.updateColumnFamily(existing, true);
            }
        }
    }

    private void syncRangeIndexWalTable(String cfName, boolean hasRangeIndexes, KeyspaceDefinition keyspaceDef)
    {
        String walCfName = cfName + "_idxwal"; 
        boolean exists = false;
        for(ColumnFamilyDefinition existing : keyspaceDef.getCfDefs())
        {
            if(existing.getName().equals(walCfName))
            {
                exists = true;
                break;
            }
        }

        if(exists && !hasRangeIndexes)
            _logger.warn("{}: does not have range indexes but 'write ahead log' table {} exists. manual drop may be safely done.", cfName, walCfName);
        //assume if the table exists, it is created correctly

        if(!exists && hasRangeIndexes)
        {
            ColumnFamilyDefinition cfDef = new BasicColumnFamilyDefinition(HFactory.createColumnFamilyDefinition(_keyspace, walCfName));
            _logger.info("{}: has range indexes - create 'write ahead log' table {}", cfName, walCfName);
            cfDef.setComparatorType(ComparatorType.UTF8TYPE);
            _cluster.addColumnFamily(cfDef, true);
        }
    }
    
    private void addCompressionOptions(ColumnFamilyDefinition def)
    {
        Map<String, String> opts = new HashMap<String, String>();

        opts.put(CompressionParameters.SSTABLE_COMPRESSION, SnappyCompressor.class.getName());
        opts.put(CompressionParameters.CHUNK_LENGTH_KB, "64");

        def.setCompressionOptions(opts);
    }
    
    private void syncRangeIndexTables(String cfName, PropertyMetadata pm, KeyspaceDefinition keyspaceDef)
    {
        String prevIdxValCfName = cfName + "_idxpval_" + pm.getPhysicalName(); 
        String idxCfName = cfName + "_idx_" + pm.getPhysicalName();
        
        boolean idxExists = false, prevValExists = false;
        for(ColumnFamilyDefinition existing : keyspaceDef.getCfDefs())
        {
            if(existing.getName().equals(idxCfName))
                idxExists = true;
            if(existing.getName().equals(prevIdxValCfName))
                prevValExists = true;
        }
        
        //assume if the tables exist, they are created correctly
        if(!idxExists && pm.isRangeIndexed())
        {
            ColumnFamilyDefinition cfDef = new BasicColumnFamilyDefinition(HFactory.createColumnFamilyDefinition(_keyspace, idxCfName));
            _logger.info("{}: create index column family {}", cfName, idxCfName);
            cfDef.setComparatorType(ComparatorType.DYNAMICCOMPOSITETYPE);
            cfDef.setComparatorTypeAlias(DynamicComposite.DEFAULT_DYNAMIC_COMPOSITE_ALIASES);
            addCompressionOptions(cfDef);
            _cluster.addColumnFamily(cfDef, true);
        }
        else if(idxExists && !pm.isRangeIndexed())
            _logger.warn("{}: range index table {} exists, but no range index is defined. index table may be safely dropped.", 
                         cfName, idxCfName);
        
        if(!prevValExists && pm.isRangeIndexed())
        {
            ColumnFamilyDefinition cfDef = new BasicColumnFamilyDefinition(HFactory.createColumnFamilyDefinition(_keyspace, prevIdxValCfName));
            _logger.info("{}: create previous value index column family {}", cfName, prevIdxValCfName);
            cfDef.setComparatorType(ComparatorType.DYNAMICCOMPOSITETYPE);
            cfDef.setComparatorTypeAlias(DynamicComposite.DEFAULT_DYNAMIC_COMPOSITE_ALIASES);
            addCompressionOptions(cfDef);
            _cluster.addColumnFamily(cfDef, true);
        }
        else if(prevValExists && !pm.isRangeIndexed())
            _logger.warn("{}: range index previous value table {} exists, but no range index is defined. index table may be safely dropped.", 
                         cfName, prevIdxValCfName);

    }

    private BasicColumnDefinition createColDef(EntityMetadata<?> meta, String familyName, PropertyMetadata pm)
    {
        BasicColumnDefinition colDef = new BasicColumnDefinition();
        colDef.setIndexName(String.format("%s_%s", familyName, pm.getPhysicalName()));
        colDef.setIndexType(ColumnIndexType.KEYS);
        colDef.setName(ByteBuffer.wrap(pm.getPhysicalNameBytes()));
        colDef.setValidationClass(BytesType.class.getName()); //skip validation
        return colDef;
    }

    private Map<String, String> compressionOptions(ColumnFamily annotation)
    {
        if(annotation.compressed())
        {
            if(annotation.compressionAlgo() == null || annotation.compressionChunkLength() <= 0)
                throw new IllegalArgumentException("invalid compression settings for " + annotation.name());
            
            Map<String, String> newOpts = new HashMap<String, String>();
            
            String compressionAlgo = annotation.compressionAlgo();
            if(compressionAlgo.equals("DeflateCompressor"))
                newOpts.put(CompressionParameters.SSTABLE_COMPRESSION, DeflateCompressor.class.getName());
            else if(compressionAlgo.equals("SnappyCompressor"))
                newOpts.put(CompressionParameters.SSTABLE_COMPRESSION, SnappyCompressor.class.getName());
            
            newOpts.put(CompressionParameters.CHUNK_LENGTH_KB, String.valueOf(annotation.compressionChunkLength()));
            return newOpts;
        }
        
        return null;
    }
}
