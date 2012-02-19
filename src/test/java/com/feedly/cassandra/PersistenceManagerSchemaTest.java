package com.feedly.cassandra;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import me.prettyprint.hector.api.ddl.ColumnDefinition;
import me.prettyprint.hector.api.ddl.ColumnFamilyDefinition;
import me.prettyprint.hector.api.ddl.ColumnIndexType;

import org.apache.cassandra.io.compress.CompressionParameters;
import org.apache.cassandra.io.compress.DeflateCompressor;
import org.apache.cassandra.io.compress.SnappyCompressor;
import org.junit.Test;

import com.feedly.cassandra.anno.ColumnFamily;
import com.feedly.cassandra.entity.enhance.CompositeIndexedBean;
import com.feedly.cassandra.entity.enhance.IndexedBean;
import com.feedly.cassandra.entity.enhance.ListBean;
import com.feedly.cassandra.entity.enhance.SampleBean;
import com.feedly.cassandra.entity.upd_enhance.SampleBean2;
import com.feedly.cassandra.test.CassandraServiceTestBase;

public class PersistenceManagerSchemaTest extends CassandraServiceTestBase
{
    @Test
    public void testSimpleTable()
    {
        PersistenceManager pm = new PersistenceManager();
        configurePersistenceManager(pm);
        
        pm.setPackagePrefixes(new String[] {SampleBean.class.getPackage().getName()});
        pm.init();
        
        String expected = SampleBean.class.getAnnotation(ColumnFamily.class).name();
        for(ColumnFamilyDefinition cfdef : cluster.describeKeyspace(KEYSPACE).getCfDefs())
        {
            if(cfdef.getName().equals(expected))
            {
                 assertTrue(cfdef.getCompressionOptions() == null || cfdef.getCompressionOptions().isEmpty());
                return;
            }
        }
        
        fail("SampleBean's table not found");
    }
    
    @Test
    public void testCompressionOptions()
    {
        PersistenceManager pm = new PersistenceManager();
        configurePersistenceManager(pm);
        
        pm.setPackagePrefixes(new String[] {ListBean.class.getPackage().getName()});
        pm.init();
        
        String expected = ListBean.class.getAnnotation(ColumnFamily.class).name();
        for(ColumnFamilyDefinition cfdef : cluster.describeKeyspace(KEYSPACE).getCfDefs())
        {
            if(cfdef.getName().equals(expected))
            {
                
                assertEquals("8", cfdef.getCompressionOptions().get(CompressionParameters.CHUNK_LENGTH_KB));
                assertEquals(DeflateCompressor.class.getName(), cfdef.getCompressionOptions().get(CompressionParameters.SSTABLE_COMPRESSION));
                return;
            }
        }
        
        fail("SampleBean's table not found");
    }
    
    @Test
    public void testHashIndexes()
    {
        PersistenceManager pm = new PersistenceManager();
        configurePersistenceManager(pm);
        
        pm.setPackagePrefixes(new String[] {IndexedBean.class.getPackage().getName()});
        pm.init();
        
        boolean foundIndexBean = false, foundCompositeIndexBean = false;
        for(ColumnFamilyDefinition cfdef : cluster.describeKeyspace(KEYSPACE).getCfDefs())
        {
            boolean isIndexBean = cfdef.getName().equals(IndexedBean.class.getAnnotation(ColumnFamily.class).name());
            boolean isCompositeIndexBean = cfdef.getName().equals(CompositeIndexedBean.class.getAnnotation(ColumnFamily.class).name());
            
            if(isIndexBean || isCompositeIndexBean)
            {
                assertEquals(2, cfdef.getColumnMetadata().size());
                for(ColumnDefinition col : cfdef.getColumnMetadata())
                {
                    assertEquals(ColumnIndexType.KEYS, col.getIndexType());
                }
            }
            
            foundIndexBean = foundIndexBean || isIndexBean;
            foundCompositeIndexBean = foundCompositeIndexBean || isCompositeIndexBean;
        }

        assertTrue(foundIndexBean);
        assertTrue(foundCompositeIndexBean);
    }

    @Test
    public void testRangeIndexes()
    {
        PersistenceManager pm = new PersistenceManager();
        configurePersistenceManager(pm);
        
        pm.setPackagePrefixes(new String[] {IndexedBean.class.getPackage().getName()});
        pm.init();
        
        Map<String, ColumnFamilyDefinition> cfLookup = new HashMap<String, ColumnFamilyDefinition>();
        String indexBeanName = IndexedBean.class.getAnnotation(ColumnFamily.class).name();
        String compositeIndexBeanName = CompositeIndexedBean.class.getAnnotation(ColumnFamily.class).name();
        
        boolean foundIndexBeanIdx = false, foundCompositeIndexBeanIdx = false;
        boolean foundIndexBeanPrevVal = false, foundCompositeIndexBeanPrevVal = false;
        boolean foundIndexBeanWal = false, foundCompositeIndexBeanWal = false;
        
        for(ColumnFamilyDefinition cfdef : cluster.describeKeyspace(KEYSPACE).getCfDefs())
        {
            String name = cfdef.getName();
            if(name.contains("_idx_"))
            {
                if(name.equals(indexBeanName + "_idx_longVal"))
                    foundIndexBeanIdx = true;
                else if(name.equals(compositeIndexBeanName + "_idx_longVal"))
                    foundCompositeIndexBeanIdx = true;
                else
                    fail("unrecognized index table " + name);
            }
            else if(name.contains("_idxpval_"))
            {
                if(name.equals(indexBeanName + "_idxpval_longVal"))
                    foundIndexBeanPrevVal = true;
                else if(name.equals(compositeIndexBeanName + "_idxpval_longVal"))
                    foundCompositeIndexBeanPrevVal = true;
                else
                    fail("unrecognized previous value index table " + name);
            }
            else if(name.endsWith("_idxwal"))
            {
                if(name.equals(indexBeanName + "_idxwal"))
                    foundIndexBeanWal = true;
                else if(name.equals(compositeIndexBeanName + "_idxwal"))
                    foundCompositeIndexBeanWal = true;
                else
                    fail("unrecognized WAL index table " + name);
            }
        }
        
        assertTrue(foundCompositeIndexBeanIdx);
        assertTrue(foundCompositeIndexBeanPrevVal);
        assertTrue(foundCompositeIndexBeanWal);
        assertTrue(foundIndexBeanIdx);
        assertTrue(foundIndexBeanPrevVal);
        assertTrue(foundIndexBeanWal);
    }
    
    @Test
    public void testUpdate()
    {
        PersistenceManager pm = new PersistenceManager();
        configurePersistenceManager(pm);
        
        pm.setPackagePrefixes(new String[] {SampleBean.class.getPackage().getName()});
        pm.init();
        
        pm = new PersistenceManager();
        configurePersistenceManager(pm);
        
        //points to same physical table as sample bean, should convert table to compressed and add an index on strVal
        pm.setPackagePrefixes(new String[] {SampleBean2.class.getPackage().getName()});
        pm.init();
        
        String expected = SampleBean.class.getAnnotation(ColumnFamily.class).name();
        for(ColumnFamilyDefinition cfdef : cluster.describeKeyspace(KEYSPACE).getCfDefs())
        {
            if(cfdef.getName().equals(expected))
            {
                 assertEquals(SnappyCompressor.class.getName(), cfdef.getCompressionOptions().get(CompressionParameters.SSTABLE_COMPRESSION));
                 assertEquals(1, cfdef.getColumnMetadata().size());
                 assertEquals(ColumnIndexType.KEYS, cfdef.getColumnMetadata().get(0).getIndexType());

                return;
            }
        }
    }

}
