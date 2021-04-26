package net.sharksystem.hub.hubside;

import net.sharksystem.asap.ASAPException;
import net.sharksystem.hub.*;
import net.sharksystem.hub.protocol.*;
import net.sharksystem.utils.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HubSession implements StreamPair {
    private final InputStream is;
    private final OutputStream os;
    private final HubInternalOld hubInternal;
    private final String peerID;

    public HubSession(InputStream is, OutputStream os, HubInternalOld hubInternal)
            throws IOException, ASAPException {

        this.is = is;
        this.os = os;
        this.hubInternal = hubInternal;

        if(this.is == null || this.os == null || this.hubInternal == null)
            throw new ASAPException("streams and hub sessions manager objects must not be null");

        // read hello pdu
        HubPDURegister hubPDURegister = (HubPDURegister) HubPDU.readPDU(this.is);
        this.peerID = hubPDURegister.peerID.toString();
        Log.writeLog(this, "new connector: " + this.peerID);

        if(this.hubInternal.isRegistered(this.peerID)) {
            Log.writeLog(this, "already connected: " + this.peerID);
            // already exists
            throw new ASAPException("already connected: " + this.peerID);
        }

        this.sendHubStatusRPLY();
    }

    public String toString() {
//        return "HubSession(" + peerID + ") is: " + is + "os: " + os;
        return "HubSession(" + peerID + ")";
    }

    private List<HubDataSession> pendingDataSessions = new ArrayList<>();
    private List<HubPDU> pendingPDUs = new ArrayList<>();

    private HubSessionProtocolEngine hubProtocolThread;

    public void start() {
        (new HubSessionProtocolEngine()).start();
        HubSession.this.hubInternal.sessionStarted(HubSession.this.peerID, HubSession.this);
    }

    private boolean dataConnectionOn = false;
    public StreamPair createDataConnection(CharSequence remotePeerID, long maxIdleInMillis) throws IOException {
        Log.writeLog(this, "create data connection called: " + this);
        // TODO - right status?
        if(this.remainSilentThread != null) this.remainSilentThread.interrupt();

        // tell connector we are ready
        HubPDUChannelClear channelClear =
                new HubPDUChannelClear(this.peerID, remotePeerID, maxIdleInMillis);

        // send channel clear pdu
        Log.writeLog(this, "send channel clear PDU: " + peerID);
        channelClear.sendPDU(this.os);

        this.dataConnectionOn = true;

        String debugIDLocal = "HubSession => Connector (" + this.peerID + ")";
        BorrowedConnection localBorrowedConnection = new BorrowedConnection(this.is, this.os,
                debugIDLocal, maxIdleInMillis);

        localBorrowedConnection.start();

        return localBorrowedConnection;
    }

    public void dataSessionEnded(StreamPair streamPair) {
        Log.writeLog(this, "data session ended: " + this);
        if(streamPair instanceof BorrowedConnection) {
            BorrowedConnection borrowedConnection = (BorrowedConnection) streamPair;

            // let thread go away - we wait for our connection to come to an end
            Thread wait4BorrowedConnection = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        //Log.writeLog(this, "data session ended:#2 " + HubSession.this);
                        borrowedConnection.close();
                        borrowedConnection.join();
                        Log.writeLog(HubSession.this, "borrowed connection finished: " + HubSession.this);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                    proceedAfterDataSession(borrowedConnection.getMaxIdleInMillis());
                }
            });
            wait4BorrowedConnection.start();
        }

        this.dataConnectionOn = false;
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////
    //                                 Hub protocol engine - hub side                               //
    //////////////////////////////////////////////////////////////////////////////////////////////////

    private class HubSessionProtocolEngine extends Thread {
        boolean again = true;
        private boolean silenceRQCalled = false;

        void silenceRQ(long duration) throws IOException {
            if(this.silenceRQCalled) {
                Log.writeLog(this, "silenceRQ already called - do not do anything: " + HubSession.this.peerID);
                return; // already called
            }

            this.silenceRQCalled = true;

            /* we are going to send a message to the connector. It is sent very fast.
            It is highly unlikely that a pdu is processed and both threads interfere. To even avoid
            this situation we synchronize with read
             */
            synchronized(this) {
                Log.writeLog(this, "send silent request to connector: " + peerID);
                new HubPDUSilentRQ(duration).sendPDU(HubSession.this.os);
            }
        }

        @Override
        public void run() {
            HubSession.this.hubProtocolThread = this; // remember read thread to interrupt

            try {
                Log.writeLog(this, "launch hub session with: " + HubSession.this.peerID);
                while (this.again) {
                    // read - will most probably block
                    //Log.writeLog(this, "before read from " + HubSession.this.peerID);
                    HubPDU hubPDU = HubPDU.readPDU(HubSession.this.is);
                    synchronized(this) {
                        int syncWithSilentRQ = 42; // we need a line of code to stop sync the process
                    }
                    //Log.writeLog(this, "received from " + HubSession.this.peerID);

                    ///// handle PDUs
                    if (hubPDU instanceof HubPDUHubStatusRQ) {
                        Log.writeLog(this, "got hub status RQ from " + HubSession.this.peerID);
                        HubSession.this.handleHubStatusRQ((HubPDUHubStatusRQ) hubPDU);
                    }

                    /* Do not throw this away - must be re-integrated
                    else if (hubPDU instanceof HubPDUConnectPeerNewTCPSocketRQ) {
                        Log.writeLog(this, this.peerID + ": connect peer RQ new tcp socket");
                        this.handleConnectPeerRQNewTCPSocket((HubPDUConnectPeerNewTCPSocketRQ) hubPDU);
                    }
                    */
                    ///////// hub connect rq - use open hub streams
                    else if (hubPDU instanceof HubPDUConnectPeerRQ) {
                        Log.writeLog(this, "got hub connect RQ from " + HubSession.this.peerID);
                        HubPDUConnectPeerRQ connectRQ = (HubPDUConnectPeerRQ) hubPDU;

                        // ask hub to establish a silent connection to this peer - asynchronous call
                        HubSession.this.hubInternal.connectionRequest(connectRQ.peerID, HubSession.this);

                    } else if (hubPDU instanceof HubPDUSilentRPLY) {
                        Log.writeLog(this, "got silent reply from " + HubSession.this.peerID);
                        // connection is silent now
                        HubPDUSilentRPLY silentRPLY = (HubPDUSilentRPLY) hubPDU;
                        this.again = false; // end this thread
                        HubSession.this.enterSilence(silentRPLY.waitDuration);
                    } else {
                        Log.writeLog(this, "got unknown PDU type from " + HubSession.this.peerID);
                    }
                }
            } catch (IOException | ASAPException e) {
                Log.writeLog(this, "connection lost to: " + HubSession.this.peerID);
                Log.writeLog(this, "remove connection to: " + HubSession.this.peerID);
                HubSession.this.hubInternal.sessionEnded(HubSession.this.peerID, HubSession.this);
            } catch (ClassCastException e) {
                Log.writeLog(this, "wrong pdu class - crazy: " + e.getLocalizedMessage());
            }
            finally {
                Log.writeLog(this, "end hub session with: " + HubSession.this.peerID);
            }
        }
    }


    private void handleHubStatusRQ(HubPDUHubStatusRQ hubPDU) throws IOException {
        this.sendHubStatusRPLY();
    }

    private void sendHubStatusRPLY() throws IOException {
        Set<CharSequence> allPeers = this.hubInternal.getRegisteredPeerIDs();
        // sort out calling peer
        //Log.writeLog(this, "assemble registered peer list for " + this.peerID);
        Set<CharSequence> peersWithoutCaller = new HashSet();
        for (CharSequence peerName : allPeers) {
//            Log.writeLog(this, peerName + " checked for peer list for " + this.peerID);
            if (!peerName.toString().equalsIgnoreCase(this.peerID)) {
                peersWithoutCaller.add(peerName);
            }
        }
        HubPDU hubInfoPDU = new HubPDUHubStatusRPLY(peersWithoutCaller);
        Log.writeLog(this, "send hub status to " + this.peerID);
        hubInfoPDU.sendPDU(this.os);
    }

    /* Do not throw this away - good code - must be re-integrated
    private void handleConnectPeerRQNewTCPSocket(HubPDUConnectPeerNewTCPSocketRQ hubPDU) throws IOException {
        Log.writeLog(this, this.peerID + ": read connect pdu");
        PeerConnectionImpl connectToPeerConnection = TCPHub.this.connectors.get(hubPDU.peerID);
        if (connectToPeerConnection == null) {
            Log.writeLog(this, "cannot connect to: " + hubPDU.peerID);
            // maybe local peer - TODO
        } else {
            ServerSocket serverSocket1 = TCPHub.this.getServerSocket();
            ServerSocket serverSocket2 = TCPHub.this.getServerSocket();
            TwistedConnection twistedConnection =
                    new TwistedConnection(serverSocket1, serverSocket2, maxIdleInSeconds);
            twistedConnection.start();

            // send to peer that asked for connection
            HubPDUConnectPeerNewConnectionRPLY hubPDUConnectPeerNewConnectionRPLY = new HubPDUConnectPeerNewConnectionRPLY(
                    serverSocket1.getLocalPort(),
                    hubPDU.peerID);

            hubPDUConnectPeerNewConnectionRPLY.sendPDU(this.peerConnection.getOutputStream());

            // send to peer that was asked to be connected
            hubPDUConnectPeerNewConnectionRPLY = new HubPDUConnectPeerNewConnectionRPLY(
                    serverSocket2.getLocalPort(),
                    this.peerConnection.getPeerID());

            hubPDUConnectPeerNewConnectionRPLY.sendPDU(connectToPeerConnection.getOutputStream());
        }
    }
     */

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////
    //                                switching hub protocol / data stream management                        //
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////

    private void proceedAfterDataSession(long syncTimeInMillis) {
        Log.writeLog(this, "proceed after data session: " + peerID);
        this.startHubProtocolEngine();

        // another silent request during the data session?
        if(this.silentRQDurationWhileDataSession > 0) {
            Log.writeLog(this, "there was another silent request in the meantime - call again: " + peerID);
            try {
                this.silentRQ(this.silentRQDurationWhileDataSession);
            } catch (IOException e) {
                Log.writeLogErr(this, "when calling silentRQ after data session: " + peerID);
                e.printStackTrace();
            }
        }
    }

    private long silentRQDurationWhileDataSession = 0;
    /**
     * Ask to become silent on connection to hub connector.
     *
     * @param duration
     * @throws IOException
     */
    public void silentRQ(long duration) throws IOException {
        Log.writeLog(this, "got silenceRQ: " + peerID  + " | " + this);
        if(this.dataConnectionOn) {
            // remember this call
            Log.writeLog(this, "data connection running: " + peerID);
            this.silentRQDurationWhileDataSession = duration;
            return;
        }
        else if(this.remainSilentThread != null) {
            // we are already waiting
            Log.writeLog(this, "remain silent thread thread running: " + peerID);
            return;
        }
        else {
            this.hubProtocolThread.silenceRQ(duration);
        }
    }

    private void enterSilence(long duration) {
        // start a thread to get control back
        this.remainSilentThread = new RemainSilentThread(duration);
        this.remainSilentThread.start();
        // notify hub after(!) that
        this.hubInternal.notifySilent(this);
    }

    public boolean isSilent() {
        return this.remainSilentThread != null;
    }

    private Thread remainSilentThread = null;
    private class RemainSilentThread extends Thread {
        private final long duration;
        RemainSilentThread(long duration) { this.duration = duration;}
        @Override
        public void run() {
            try {
                Log.writeLog(this, "remain silent:  " + this.duration + " ms: " + peerID);
                Thread.sleep(duration);
                // woke up - restart hub protocol engine
                Log.writeLog(this, "silence ended - restart hub session protocol engine: " + peerID);
                HubSession.this.startHubProtocolEngine();
            } catch (InterruptedException e) {
                Log.writeLog(this, "remain silent thread interrupted in session " + HubSession.this.peerID);
            }
        }
    }

    private void startHubProtocolEngine() {
        Log.writeLog(this, "restart hub session protocol engine: " + peerID);
        (new Thread(new HubSessionProtocolEngine())).start();
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //                                              data session                                               //
    /////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /*
    private HubDataSession hubDataSession = null;
    public void openDataSession(SessionConnection otherSession) throws IOException {
        // kill remain silent thread - which is there..
        if(this.remainSilentThread != null) {
            this.remainSilentThread.interrupt();
        }
        // put data session object in queue DataSession object
        this.hubDataSession = new HubDataSession(this, otherSession, this.is, this.os, this.hub.getMaxIdleInMillis());
        this.hubDataSession.start();
    }
     */


    /////////////////////////////////////////////////////////////////////////////////////////////////
    //                                         hub session connection                              //
    /////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public InputStream getInputStream() {
        return new InputStreamWrapper(this.is);
    }

    @Override
    public OutputStream getOutputStream() {
        return new OutputStreamWrapper(this.os);
    }

    public CharSequence getPeerID() {
        return this.peerID;
    }

    @Override
    public void close() {
        // TODO
    }

    private class InputStreamWrapper extends InputStream {
        private final InputStream is;
        private boolean closed = false;

        InputStreamWrapper(InputStream is) {
            this.is = is;
        }

        @Override
        public int read() throws IOException {
            if(this.closed) throw new IOException("stream close");
            return this.is.read();
        }

        public void close() {
            // do not close the stream
            this.closed = true;
            HubSession.this.close();
        }
    }

    private class OutputStreamWrapper extends OutputStream {
        private final OutputStream os;
        private boolean closed = false;

        OutputStreamWrapper(OutputStream os) {
            this.os = os;
        }

        @Override
        public void write(int value) throws IOException {
            if(this.closed) throw new IOException("stream close");
            this.os.write(value);
        }

        public void close() {
            // do not close the stream
            this.closed = true;
            HubSession.this.close();
        }
    }
}
