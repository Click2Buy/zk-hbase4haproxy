package com.marketconnect.zkhbase4haproxy;

import com.google.protobuf.InvalidProtocolBufferException;
    
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

import org.apache.log4j.Logger;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupProtos;

public class Agent implements Watcher, Runnable {

    private static final byte [] PB_MAGIC = new byte [] {'P', 'B', 'U', 'F'};

    private static Logger log = Logger.getLogger(Agent.class);
    private boolean dead = false;
    private RSGroupProtos.RSGroupInfo rsList;
    private RSGroupProtos.RSGroupInfo defaultRsList;
    private String rsState = "UP";
    private String hbaseRsGroup;
    private boolean manualRecovery = false;
    private ZooKeeper zkClient;
    private ServerSocket serverSocket;

    public static void main(String[] args) {
        ArgumentParser parser = ArgumentParsers.newFor("Agent").build()
            .defaultHelp(true)
            .description("Haproxy agent checking checking hbase zookeeper configuration to return UP or DOWN.");
        parser.addArgument("--zkQuorum")
            .required(true)
            .help("Quorum Zookeeper to connect to.");
        parser.addArgument("--hbaseRsGroup")
            .help("Hbase' RS Group to monitor if the functionnality is used.");
        parser.addArgument("--manualRecovery")
            .action(Arguments.storeTrue())
            .help("The agent should not go back to UP status after a DOWN even if region server are available (default is false).");
        parser.addArgument("--port")
            .type(Integer.class)
            .setDefault(9999)
            .help("Port to listen haproxy incoming check (default is 9999).");
        Namespace ns = null;
        try {
            ns = parser.parseArgs(args);
        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(1);
        }

        try {
            new Agent(ns.getString("zkQuorum"),
                      ns.getString("hbaseRsGroup"),
                      ns.getBoolean("manualRecovery"),
                      ns.getInt("port")
                      ).run();
        } catch (Exception e) {
            log.error(e);
        }
    }

    // Find key byte array in buffer byte array
    public int find(byte[] buffer, byte[] key) {
        for (int i = 0; i <= buffer.length - key.length; i++) {
            int j = 0;
            while (j < key.length && buffer[i + j] == key[j]) {
                j++;
            }
            if (j == key.length) {
                return i;
            }
        }
        return -1;
    }

    private RSGroupProtos.RSGroupInfo listRs(byte[] data)
        throws InvalidProtocolBufferException, UnsupportedEncodingException {
        int start = find(data, PB_MAGIC) + 4;
        byte[] subdata = Arrays.copyOfRange(data, start, data.length);
        return RSGroupProtos.RSGroupInfo.parseFrom(subdata);
    }

    private void updateRsState(List<String> rsChildren) 
    {
        if (manualRecovery && "DOWN".equals(rsState)) {
            return;
        }

        // we monitor only 1 RS group + default group
        boolean rsListFound = false;
        boolean defaultRsListFound = false;
        if (rsList != null) {
            for (String rs : rsChildren) {
                for (HBaseProtos.ServerName serverName : rsList.getServersList()) {
                    if (rs.startsWith(serverName.getHostName() + "," + serverName.getPort() + ",")) {
                        rsListFound = true;
                        break;
                    }
                }
                for (HBaseProtos.ServerName defaultServerName : defaultRsList.getServersList()) {
                    if (rs.startsWith(defaultServerName.getHostName() + "," + defaultServerName.getPort() + ",")) {
                        defaultRsListFound = true;
                        break;
                    }
                }
                if (rsListFound && defaultRsListFound) {
                    rsState = "UP";
                    return;
                }
            }
            rsState = "DOWN";
            log.error("All region servers are down.");
            return;
        }

        // we monitor all the rs
        if (rsChildren.size() > 0) {
            rsState = "UP";
            return;
        }

        rsState = "DOWN";
        log.error("All region servers are down.");
    }

    public Agent(String zkQuorum, String hbaseRsGroup, boolean manualRecovery, int port)
        throws KeeperException, IOException, InterruptedException {
        zkClient = new ZooKeeper(zkQuorum, 5000, this);
        this.manualRecovery = manualRecovery;
        this.hbaseRsGroup = hbaseRsGroup;

        if (hbaseRsGroup != null) {
            /**
             * We want to check default group no matter what,
             * because it has hbase meta tables
             */
            byte[] data = null;
            data = zkClient.getData("/rsgroup/default",
                                    this,
                                    new Stat());
            defaultRsList = listRs(data);
            data = zkClient.getData("/rsgroup/" + hbaseRsGroup,
                                    this,
                                    new Stat());
            rsList = listRs(data);

            List<String> rsChildren = zkClient.getChildren("/rs", this);
            updateRsState(rsChildren);
        } else {
            List<String> rsChildren = zkClient.getChildren("/rs", this);
            updateRsState(rsChildren);
        }
        serverSocket = new ServerSocket(port);
    }

    public void process(WatchedEvent event) {
        String path = event.getPath();
        if (event.getType() == Event.EventType.None) {
            // We are are being told that the state of the
            // connection has changed
            switch (event.getState()) {
            case SyncConnected:
                // In this particular example we don't need to do anything
                // here - watches are automatically re-registered with 
                // server and any watches triggered while the client was 
                // disconnected will be delivered (in order of course)
                break;
            case Expired:
                // It's all over
                dead = true;
                break;
            default:
                break;
            }
        } else {
            if ("/rs".equals(path)) {
                try {
                    List<String> rsChildren = zkClient.getChildren("/rs", this);
                    updateRsState(rsChildren);
                } catch (Exception e) {
                    log.error(e);
                }
            } else if (hbaseRsGroup != null) {
                byte[] data = null;
                if ("/rsgroup/default".equals(path)) {
                    try {
                        data = zkClient.getData("/rsgroup/default",
                                                this,
                                                new Stat());
                    } catch (KeeperException e) {
                        log.error(e);
                    } catch (InterruptedException e) {
                        log.error(e);
                    }
                    try {
                        defaultRsList = listRs(data);
                    } catch (UnsupportedEncodingException e) {
                        log.error(e);
                    } catch (InvalidProtocolBufferException e) {
                        log.error(e);
                    }
                }
                if (("/rsgroup/" + hbaseRsGroup).equals(path)) {
                    try {
                        data = zkClient.getData("/rsgroup/" + hbaseRsGroup,
                                                this,
                                                new Stat());
                    } catch (KeeperException e) {
                        log.error(e);
                    } catch (InterruptedException e) {
                        log.error(e);
                    }
                    try {
                        rsList = listRs(data);
                    } catch (InvalidProtocolBufferException e) {
                        log.error(e);
                    } catch (UnsupportedEncodingException e) {
                        log.error(e);
                    }
                }
            }
        }
    }

    public void run() {
        while (!dead) {
            try {
                Socket clientSocket = serverSocket.accept();
                PrintWriter out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream(),
                                                                         StandardCharsets.UTF_8),
                                                  true);
                out.println(rsState);
                out.close();
                clientSocket.close();
            } catch (IOException e) {
                log.error(e);
            }
        }
        try {
            zkClient.close();
            serverSocket.close();
        } catch (Exception e) {
            log.error(e);
        }
    }

}
