package com.feedly.cassandra;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import me.prettyprint.cassandra.model.AllOneConsistencyLevelPolicy;
import me.prettyprint.cassandra.model.BasicColumnDefinition;
import me.prettyprint.cassandra.model.BasicColumnFamilyDefinition;
import me.prettyprint.cassandra.model.QuorumAllConsistencyLevelPolicy;
import me.prettyprint.cassandra.serializers.StringSerializer;
import me.prettyprint.cassandra.service.CassandraHostConfigurator;
import me.prettyprint.cassandra.service.OperationType;
import me.prettyprint.cassandra.service.ThriftKsDef;
import me.prettyprint.hector.api.Cluster;
import me.prettyprint.hector.api.ConsistencyLevelPolicy;
import me.prettyprint.hector.api.HConsistencyLevel;
import me.prettyprint.hector.api.Keyspace;
import me.prettyprint.hector.api.beans.DynamicComposite;
import me.prettyprint.hector.api.ddl.ColumnDefinition;
import me.prettyprint.hector.api.ddl.ColumnFamilyDefinition;
import me.prettyprint.hector.api.ddl.ColumnIndexType;
import me.prettyprint.hector.api.ddl.ComparatorType;
import me.prettyprint.hector.api.ddl.KeyspaceDefinition;
import me.prettyprint.hector.api.factory.HFactory;

import org.apache.cassandra.db.compaction.LeveledCompactionStrategy;
import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.io.compress.CompressionParameters;
import org.apache.cassandra.io.compress.DeflateCompressor;
import org.apache.cassandra.io.compress.SnappyCompressor;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feedly.cassandra.anno.ColumnFamily;
import com.feedly.cassandra.entity.EIndexType;
import com.feedly.cassandra.entity.EntityMetadata;
import com.feedly.cassandra.entity.IndexMetadata;
import com.feedly.cassandra.entity.PropertyMetadataBase;
import com.feedly.cassandra.entity.SimplePropertyMetadata;
import com.feedly.cassandra.entity.enhance.IEnhancedEntity;

public class PersistenceManager implements IKeyspaceFactory
{
    /**
     * The column family used to log range index writes. Data can be used to make data consistent if a problem occurs during a range indexed 
     * write.
     */
    public static final String CF_IDXWAL = "fc_idxwal";

    private static final Logger _logger = LoggerFactory.getLogger(PersistenceManager.class.getName());
    private static final Map<EConsistencyLevel, ConsistencyLevelPolicy> _consistencyMapping;
    
    private boolean _syncSchema = true;
    private String[] _sourcePackages = new String[0];
    private Set<Class<?>> _colFamilies;
    private String _keyspace;
    private CassandraHostConfigurator _hostConfig;
    private String _clusterName;
    private Cluster _cluster;
    private int _replicationFactor = 1;
    
    static
    {
        Map<EConsistencyLevel, ConsistencyLevelPolicy> consistencyMapping = new HashMap<EConsistencyLevel, ConsistencyLevelPolicy>();
        consistencyMapping.put(EConsistencyLevel.ONE, new AllOneConsistencyLevelPolicy());
        consistencyMapping.put(EConsistencyLevel.QUOROM, new QuorumAllConsistencyLevelPolicy());
        consistencyMapping.put(EConsistencyLevel.ALL, 
                               new ConsistencyLevelPolicy()
                               {
                                    @Override
                                    public HConsistencyLevel get(OperationType op)
                                    {
                                        return HConsistencyLevel.ALL;
                                    }
                        
                                    @Override
                                    public HConsistencyLevel get(OperationType op, String cfName)
                                    {
                                        return HConsistencyLevel.ALL;
                                    }
                
                               });
        
        _consistencyMapping = Collections.unmodifiableMap(consistencyMapping);
    }
    
    public void setReplicationFactor(int r)
    {
        _replicationFactor = r;
    }
    
    public void setClusterName(String cluster)
    {
        _clusterName = cluster;
    }
    public void setKeyspaceName(String keyspace)
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
    
    public void destroy()
    {
        try
        {
            _logger.info("stopping cassandra cluster");
            HFactory.shutdownCluster(_cluster);
        }
        catch(Exception ex)
        {
            _logger.error("error shutting down cluster", ex);
        }
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
                _logger.warn(family.getName() + " has not been enhanced after compilation, it will be ignored. See EntityTransformerTask");
                
                iter.remove();
            }
        }

        _colFamilies = Collections.unmodifiableSet(annotated);
        
        _cluster = HFactory.getOrCreateCluster(_clusterName, _hostConfig);
        if(_syncSchema)
            syncKeyspace();
    }
    
    public Set<Class<?>> getColumnFamilies()
    {
        return _colFamilies;
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

        boolean walExists = false;
        for(ColumnFamilyDefinition cfdef : kdef.getCfDefs())
        {
            if(cfdef.getName().equals(CF_IDXWAL))
            {
                _logger.debug("'write ahead log' column family {} already exists", CF_IDXWAL);
                walExists = true;
                break;
            }
        }

        if(!walExists)
        {
            ColumnFamilyDefinition cfDef = new BasicColumnFamilyDefinition(HFactory.createColumnFamilyDefinition(_keyspace, CF_IDXWAL));
            cfDef.setCompactionStrategy(LeveledCompactionStrategy.class.getSimpleName());
            cfDef.setGcGraceSeconds(0);//keeps row sizes small and we don't care about inter node consistency, just do extra work on phantom reads
            _logger.info("creating 'write ahead log' column family {}", CF_IDXWAL);
            cfDef.setComparatorType(ComparatorType.COMPOSITETYPE);
            cfDef.setComparatorTypeAlias(String.format("(%s, %s)", ComparatorType.LONGTYPE.getTypeName(), ComparatorType.BYTESTYPE.getTypeName()));
            addCompressionOptions(cfDef);
            _cluster.addColumnFamily(cfDef, true);
        }
        for(Class<?> family : _colFamilies)
        {
            syncColumnFamily(family, kdef);
        }
    }
    
    public Keyspace createKeyspace(EConsistencyLevel level)
    {
        return HFactory.createKeyspace(_keyspace, _cluster, _consistencyMapping.get(level));
    }
    
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void syncColumnFamily(Class<?> family, KeyspaceDefinition keyspaceDef)
    {
        ColumnFamily annotation = family.getAnnotation(ColumnFamily.class);
        EntityMetadata<?> meta = new EntityMetadata(family);
        
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
        
        Map<String, String> compressionOptions = null;
        if(existing == null)
        {
            Set<String> hashIndexed = new HashSet<String>();
            Set<String> rangeIndexed = new HashSet<String>();

            if(meta.hasNormalColumns())
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
                
                for(IndexMetadata im : meta.getIndexes())
                {
                    if(im.getType() == EIndexType.HASH)
                    {
                        SimplePropertyMetadata pm = im.getIndexedProperties().get(0);
                        cfDef.addColumnDefinition(createColDef(meta, familyName, pm)); //must be exactly 1 prop
                        hashIndexed.add(pm.getPhysicalName());
                    }
                    else
                    {
                        rangeIndexed.add(im.id());
                    }
                }
                
                syncRangeIndexFamilies(meta, !rangeIndexed.isEmpty(), keyspaceDef);
                compressionOptions = compressionOptions(annotation);
                cfDef.setCompressionOptions(compressionOptions);
                
                
                _cluster.addColumnFamily(cfDef, true);
            }
            syncCounterFamily(meta, keyspaceDef);

            _logger.info("{}: compression options: {}, hash indexed columns: {}, range indexed columns {}", 
                         new Object[] {familyName, compressionOptions, hashIndexed, rangeIndexed});
        }
        else 
        {
            existing = new BasicColumnFamilyDefinition(existing);
            compressionOptions = existing.getCompressionOptions();
            boolean doUpdate = false;
            boolean hasRangeIndexes = false;
            
            Set<SimplePropertyMetadata> hashIndexedProps = new HashSet<SimplePropertyMetadata>();
            for(IndexMetadata im : meta.getIndexes())
            {
                if(im.getType() == EIndexType.HASH)
                    hashIndexedProps.add(im.getIndexedProperties().get(0)); //must be exactly 1
                else
                    hasRangeIndexes = true;
            }
            
            /*
             * check if existing column metadata is in sync, be sure not to mutate the cols as some may be 
             * sent back to the server. Don't touch byte buffers, etc.
             */
            for(ColumnDefinition colMeta : existing.getColumnMetadata())
            {
                String colName;
                if(meta.useCompositeColumns())
                {
                    DynamicComposite col = DynamicComposite.fromByteBuffer(colMeta.getName().duplicate());
                    Object prop1 = col.get(0);
                    if(prop1 instanceof String)
                        colName = (String) prop1;
                    else
                        colName = null;
                }
                else
                    colName = StringSerializer.get().fromByteBuffer(colMeta.getName().duplicate());
                
                PropertyMetadataBase pm = meta.getPropertyByPhysicalName(colName);
                if(pm != null)
                {
                    boolean isHashIndexed = hashIndexedProps.remove(pm);
                    _logger.info("index on {}.{} exists", familyName, pm.getPhysicalName()); 

                    if(colMeta.getIndexType() != null && !isHashIndexed)
                        _logger.warn("{}.{} is indexed in cassandra, but not in the data model. manual intervention needed", 
                                     familyName, pm.getPhysicalName());
                    
                    if(colMeta.getIndexType() == null && isHashIndexed)
                        throw new IllegalStateException(familyName + "." + pm.getPhysicalName() + 
                                " is not indexed in cassandra, manually add the index and then restart");
                }
                else
                {
                    _logger.warn("encountered unmapped column {}.{}", familyName, colName);
                }
            }
            
            syncRangeIndexFamilies(meta, hasRangeIndexes, keyspaceDef);
            syncCounterFamily(meta, keyspaceDef);

            for(SimplePropertyMetadata pm : hashIndexedProps)
            {
                existing.addColumnDefinition(createColDef(meta, familyName, pm));
                _logger.info("adding index on {}.{}", familyName, pm.getPhysicalName()); 
                doUpdate = true;
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

    private void syncCounterFamily(EntityMetadata<?> meta, KeyspaceDefinition keyspaceDef)
    {
        boolean exists = false;
        for(ColumnFamilyDefinition existing : keyspaceDef.getCfDefs())
        {
            if(existing.getName().equals(meta.getCounterFamilyName()))
            {
                exists = true;
                break;
            }
        }
        
        if(exists && !meta.hasCounterColumns())
        {
            _logger.warn("{}: does not have counter columns but 'counter' column family {} exists. manual drop may be safely done.", 
                         meta.getFamilyName(), meta.getCounterFamilyName());
        }
        else if(!exists && meta.hasCounterColumns())
        {
            _logger.info("{}: has counters - create 'counter' column family {}", meta.getFamilyName(), meta.getCounterFamilyName());
            ColumnFamilyDefinition cfDef = new BasicColumnFamilyDefinition(HFactory.createColumnFamilyDefinition(_keyspace, meta.getCounterFamilyName()));
            cfDef.setDefaultValidationClass(ComparatorType.COUNTERTYPE.getTypeName());
            addCompressionOptions(cfDef);
            _cluster.addColumnFamily(cfDef, true);
        }
    }

    private void syncRangeIndexFamilies(EntityMetadata<?> meta, boolean hasRangeIndexes, KeyspaceDefinition keyspaceDef)
    {
        boolean idxExists = false;
        for(ColumnFamilyDefinition existing : keyspaceDef.getCfDefs())
        {
            if(existing.getName().equals(meta.getIndexFamilyName()))
            {
                idxExists = true;
                break;
            }
        }

        
        if(!hasRangeIndexes && idxExists)
        {
            _logger.warn("{}: does not have range indexes but 'index' column family {} exists. manual drop may be safely done.", 
                         meta.getFamilyName(), meta.getIndexFamilyName());
        }
        else if(hasRangeIndexes && !idxExists)
        {
            ColumnFamilyDefinition cfDef = new BasicColumnFamilyDefinition(HFactory.createColumnFamilyDefinition(_keyspace, meta.getIndexFamilyName()));
            _logger.info("{}: has range indexes - create 'index' column family {}", meta.getFamilyName(), meta.getIndexFamilyName());
            cfDef.setComparatorType(ComparatorType.DYNAMICCOMPOSITETYPE);
            cfDef.setComparatorTypeAlias(DynamicComposite.DEFAULT_DYNAMIC_COMPOSITE_ALIASES);
            addCompressionOptions(cfDef);
            _cluster.addColumnFamily(cfDef, true);
        }
        //assume if the table exists, it is created correctly
    }
    
    private void addCompressionOptions(ColumnFamilyDefinition def)
    {
        Map<String, String> opts = new HashMap<String, String>();

        opts.put(CompressionParameters.SSTABLE_COMPRESSION, SnappyCompressor.class.getName());
        opts.put(CompressionParameters.CHUNK_LENGTH_KB, "64");

        def.setCompressionOptions(opts);
    }
    
    private BasicColumnDefinition createColDef(EntityMetadata<?> meta, String familyName, SimplePropertyMetadata pm)
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

        return Collections.singletonMap(CompressionParameters.SSTABLE_COMPRESSION, "");
    }

}
