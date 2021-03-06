package com.feedly.cassandra.test;

import java.io.IOException;

import org.apache.cassandra.thrift.CassandraDaemon;
import org.apache.thrift.transport.TTransportException;

public class EmbeddedCassandraService implements Runnable
{
    private CassandraDaemon cassandraDaemon;
    private boolean started = false;
    
    public void init() throws TTransportException, IOException
    {
        cassandraDaemon = new CassandraDaemon();
        cassandraDaemon.init(null);
    }
 
    @Override
    public void run()
    {
        cassandraDaemon.start();
    }
    
    public void stop()
    {
        cassandraDaemon.stop();
    }
    
    public synchronized boolean cleanStart(String keyspace, String snapshotFile) throws TTransportException, IOException, InterruptedException
    {
        if(started)
        {
            System.out.println("already started!");
            return false;
        }
        
        started = true;
        System.out.println("starting...");
        // Tell cassandra where the configuration files are.
        // Use the test configuration file.
        System.setProperty("cassandra.config", "cassandra/conf/cassandra.yaml");

        CassandraServiceDataCleaner cleaner = new CassandraServiceDataCleaner();
        cleaner.prepare(keyspace, snapshotFile);
        init();
        Thread t = new Thread(this);
        t.setDaemon(true);
        t.start();
        
        return true;
    }
}
