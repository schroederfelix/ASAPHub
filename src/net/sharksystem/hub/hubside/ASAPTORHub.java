package net.sharksystem.hub.hubside;

import net.sf.T0rlib4j.controller.network.JavaTorRelay;
import net.sf.T0rlib4j.controller.network.TorServerSocket;
import net.sharksystem.asap.ASAPException;
import net.sharksystem.hub.Connector;
import net.sharksystem.hub.protocol.ConnectorThread;
import net.sharksystem.utils.Commandline;
import net.sharksystem.utils.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public class ASAPTORHub extends HubSingleEntitySharedChannel implements Runnable {
    public static final int DEFAULT_MAX_IDLE_CONNECTION_IN_SECONDS = 60;
    private final boolean newConnection;
    private int maxIdleInMillis = DEFAULT_MAX_IDLE_CONNECTION_IN_SECONDS * 1000;

    private static final Logger LOG = LoggerFactory.getLogger(ServerSocketViaTor.class);
    private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int hiddenservicedirport = 80;
    private static final int localport = 2096;
//    private static final int localport = DEFAULT_PORT;

    private static CountDownLatch serverLatch = new CountDownLatch(2);

    private final int port;
    private final ServerSocket serverSocket;
    private int minPort = 0;
    private int maxPort = 0;
    private int nextPort = 0;

    private boolean killed = false;
    private StatusPrinter statusPrinter;
    private LocalDateTime now;

    public ASAPTORHub() throws IOException {
        this(DEFAULT_PORT);
    }

    public ASAPTORHub(int port) throws IOException {
        this(port, false);
    }

    public ASAPTORHub(int port, boolean newConnection) throws IOException {


        File dir = new File("torfiles");

        JavaTorRelay node = new JavaTorRelay(dir);
        TorServerSocket torServerSocket = node.createHiddenService(localport, hiddenservicedirport);
        System.out.println("Hidden Service Binds to   " + torServerSocket.getHostname() + " ");
        System.out.println("Tor Service Listen to Port  " + torServerSocket.getServicePort());
        this.port = torServerSocket.getServicePort();
        this.nextPort = port+1;
        this.serverSocket = torServerSocket.getServerSocket();
        System.out.println("ServerSocket: "+this.serverSocket);
        this.newConnection = newConnection;
    }

    public void setPortRange(int minPort, int maxPort) throws ASAPException {
        if(minPort < -1 || maxPort < -1 || maxPort <= minPort) {
            throw new ASAPException("port number must be > 0 and max > min");
        }

        this.minPort = minPort;
        this.maxPort = maxPort;

        this.nextPort = this.minPort;
    }

    public void setMaxIdleConnectionInSeconds(int maxIdleInSeconds) {
        this.maxIdleInMillis = maxIdleInSeconds * 1000;
    }

    synchronized ServerSocket getServerSocket() throws IOException {
        if(this.minPort == 0 || this.maxPort == 0) {
            return new ServerSocket(0);
        }

        int port = this.nextPort++;
        // try
        while(port <= this.maxPort) {
            try {
                ServerSocket srv = new ServerSocket(port);
                return srv;
            } catch (IOException ioe) {
                // port already in use
            }
            port = this.nextPort++;
        }
        this.nextPort = this.minPort; // rewind for next round
        throw new IOException("all ports are in use");
    }

    @Override
    public void run() {
        Log.writeLog(this, "Got started on port: " + this.port);
        while(!killed) {
            Socket newConnection = null;
            try {
                newConnection =
                        this.serverSocket.accept();
            }
            catch(IOException ioe) {
                Log.writeLog(this, "exception when going to accept TCP connections - fatal, give up: "
                        + ioe.getLocalizedMessage());
                return;
            }

            Log.writeLog(this, "new TCP connection - launch hub connector session");


            try {
                System.out.println("Test before HubConnectorSession");
                this.now = LocalDateTime.now();
                System.out.println("Accepted Client at Address - " +  newConnection.getRemoteSocketAddress()
                                           + " on port " + newConnection.getLocalPort() + " at time " + dtf.format(now));
//                ObjectInputStream in = new ObjectInputStream(newConnection.getInputStream());
//                System.out.println((String) in.readObject());
//                System.out.println("Test before HubConnectorSession");

                Connector hubConnectorSession;
                if(this.newConnection) {
                    hubConnectorSession =
                            new MultipleTORChannelsConnectorHubSideImpl(
                                    newConnection.getInputStream(), newConnection.getOutputStream(), this);
                } else {
                    // another connector has connected
                    hubConnectorSession = new SharedChannelConnectorHubSideImpl(
                            newConnection.getInputStream(), newConnection.getOutputStream(), this);
                }
                (new ConnectorThread(hubConnectorSession, newConnection.getInputStream())).start();
            } catch (IOException | ASAPException e) {
                // gone
                Log.writeLog(this, "hub connector session ended: " + e.getLocalizedMessage() +"("+ e+")");
            }
        }
    }

    Map<CharSequence, Set<CharSequence>> connectionRequests = new HashMap<>();

    private String connectionRequestsToString() {
        StringBuilder sb = new StringBuilder();
        sb.append("connection requests: ");

        Set<CharSequence> peerIDs = this.connectionRequests.keySet();
        if(peerIDs == null || peerIDs.isEmpty()) {
            sb.append("empty");
        } else {
            for(CharSequence peerID : peerIDs) {
                sb.append("\n"); sb.append(peerID); sb.append(": ");
                Set<CharSequence> otherPeerIDs = this.connectionRequests.get(peerID);
                boolean first = true;
                for(CharSequence otherPeerID : otherPeerIDs) {
                    if(first) { first = false; } else {sb.append(", ");}
                    sb.append(otherPeerID);
                }
            }
        }

        return sb.toString();
    }

    /**
     * Remember that peerA wants to connect to peerB
     * @param peerA
     * @param peerB
     */
    private void rememberConnectionRequest(CharSequence peerA, CharSequence peerB) {
        synchronized (this.connectionRequests) {
            Set<CharSequence> otherPeers = this.connectionRequests.get(peerA);
            if (otherPeers == null) {
                otherPeers = new HashSet<>();
                this.connectionRequests.put(peerA, otherPeers);
            }

            otherPeers.add(peerB);
        }
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////
    //                                              command line                                           //
    /////////////////////////////////////////////////////////////////////////////////////////////////////////

    public static void main(String[] args) throws IOException {
        String usageString = "optional parameters: -port [portnumber] -maxIdleSeconds [seconds]";

        // now get real parameters
        HashMap<String, String> argumentMap = Commandline.parametersToMap(args,
                false, usageString);

        int port = DEFAULT_PORT;
        int maxIdleInSeconds = -1;

        if(argumentMap != null) {
            Set<String> keys = argumentMap.keySet();
            if(keys.contains("-help") || keys.contains("-?")) {
                System.out.println(usageString);
                System.exit(0);
            }

            // port defined
            String portString = argumentMap.get("-port");
            if(portString != null) {
                try {
                    port = Integer.parseInt(portString);
                } catch (RuntimeException re) {
                    System.err.println("port number must be a numeric: " + portString);
                    System.exit(0);
                }
            }

            // max idle in seconds?
            String maxIdleInSecondsString = argumentMap.get("-maxIdleInSeconds");
            if(maxIdleInSecondsString != null) {
                try {
                    maxIdleInSeconds = Integer.parseInt(maxIdleInSecondsString);
                } catch (RuntimeException re) {
                    System.err.println("maxIdleInSeconds must be a numeric: " + maxIdleInSecondsString);
                    System.exit(0);
                }
            }
        }

        // create TCPHub
        ASAPTORHub.startTCPHubThread(port, true, maxIdleInSeconds);
    }

    public static ASAPTORHub startTCPHubThread(int port, boolean multichannel, int maxIdleInSeconds)
            throws IOException {

        ASAPTORHub tcpHub = new ASAPTORHub(port, multichannel);
        if(maxIdleInSeconds > 0) {
            tcpHub.setMaxIdleConnectionInSeconds(maxIdleInSeconds);
        }

        System.out.println("start TCP hub on port " + tcpHub.port
                + " with maxIdleInSeconds: " + tcpHub.maxIdleInMillis / 1000);

        tcpHub.startStatusPrinter();
        new Thread(tcpHub).start();
        return tcpHub;
    }

    public void kill() {
        this.killed = true;

        try {
            this.serverSocket.close();
        } catch (IOException e) {
            Log.writeLog(this, "cannot close server socket: " + e.getLocalizedMessage());
        }

        if(this.statusPrinter != null) {
            this.statusPrinter.kill();
        }
    }

    private void startStatusPrinter() {
        this.statusPrinter = new StatusPrinter(this);
        this.statusPrinter.start();
    }

    private static class StatusPrinter extends Thread {
        private final ASAPTORHub tcpHub;
        private boolean stopped = false;

        StatusPrinter(ASAPTORHub tcpHub) {
            this.tcpHub = tcpHub;
        }

        public void run() {
            while(!this.stopped) {
                System.out.println("registered peers: " + this.tcpHub.getRegisteredPeers());
                try {
                    Thread.sleep(30000);
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }

        public void kill() {
            this.stopped = true;
        }
    }
}