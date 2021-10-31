import java.util.ArrayList;

/**
 * <p>Title: CPSC 433/533 Programming Assignment</p>
 *
 * <p>Description: Fishnet socket implementation</p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 *
 * <p>Company: Yale University</p>
 *
 * @author Hao Wang
 * @version 1.0
 */

public class TCPSock {
    // TCPManager of Socket
    TCPManager tcpMan;
    public int srcPort;
    public int destPort;
    public int srcAddr;
    public int destAddr;

    int currPendingConnections;
    public ArrayList<TCPSock> backlogSockets;

    // TCP socket states
    public static enum State {
        // protocol states
        CLOSED,
        LISTEN,
        SYN_SENT,
        ESTABLISHED,
        SHUTDOWN // close requested, FIN not sent (due to unsent data in queue)
    }
    public State state;

    public TCPSock(TCPManager tcpMan) {
        this.tcpMan = tcpMan;
        this.currPendingConnections = 0;
        this.backlogSockets = new ArrayList<TCPSock>();
        state = State.CLOSED;
    }

    /*
     * The following are the socket APIs of TCP transport service.
     * All APIs are NON-BLOCKING.
     */

    /**
     * Bind a socket to a local port
     *
     * @param localPort int local port number to bind the socket to
     * @return int 0 on success, -1 otherwise
     */
    public int bind(int localPort) {
        int foundPort = tcpMan.findOpenPort(localPort);
        this.srcPort = localPort;
        this.state = State.CLOSED;
        tcpMan.tcpSocksInUse.add(this);
        return foundPort;
    }

    /**
     * Listen for connections on a socket
     * @param backlog int Maximum number of pending connections
     * @return int 0 on success, -1 otherwise
     */
    public int listen(int backlog) {
        this.state = State.LISTEN;
        return 0;
    }

    /**
     * Accept a connection on a socket
     *
     * @return TCPSock The first established connection on the request queue
     */
    public TCPSock accept() {
        if (backlogSockets.isEmpty()){
            return null;
        }
        TCPSock firstOnQueue = backlogSockets.get(0);
        backlogSockets.remove(0);
        firstOnQueue.state = State.ESTABLISHED;
        return firstOnQueue;
    }

    public boolean isConnectionPending() {
        return (state == State.SYN_SENT);
    }

    public boolean isClosed() {
        return (state == State.CLOSED);
    }

    public boolean isConnected() {
        return (state == State.ESTABLISHED);
    }

    public boolean isClosurePending() {
        return (state == State.SHUTDOWN);
    }

    /**
     * Initiate connection to a remote socket
     *
     * @param destAddr int Destination node address
     * @param destPort int Destination port
     * @return int 0 on success, -1 otherwise
     */
    public int connect(int destAddr, int destPort) {
        byte[] transportPktPayload = {};
        Transport transportPkt = new Transport(this.srcPort, destPort, 0, 1, 1, transportPktPayload);
        tcpMan.node.sendSegment(tcpMan.addr, destAddr, Protocol.TRANSPORT_PKT, transportPkt.pack());
        this.state = State.SYN_SENT;
        this.destAddr = destAddr;
        this.destPort = destPort;
        this.srcAddr = tcpMan.addr;
        // add timer here at some point ...
        return 0;
    }

    /**
     * Initiate closure of a connection (graceful shutdown)
     */
    public void close() {
    }

    /**
     * Release a connection immediately (abortive shutdown)
     */
    public void release() {
    }

    /**
     * Write to the socket up to len bytes from the buffer buf starting at
     * position pos.
     *
     * @param buf byte[] the buffer to write from
     * @param pos int starting position in buffer
     * @param len int number of bytes to write
     * @return int on success, the number of bytes written, which may be smaller
     *             than len; on failure, -1
     */
    public int write(byte[] buf, int pos, int len) {
        return -1;
    }

    /**
     * Read from the socket up to len bytes into the buffer buf starting at
     * position pos.
     *
     * @param buf byte[] the buffer
     * @param pos int starting position in buffer
     * @param len int number of bytes to read
     * @return int on success, the number of bytes read, which may be smaller
     *             than len; on failure, -1
     */
    public int read(byte[] buf, int pos, int len) {
        return -1;
    }

    public void onReceive(Transport transportPkt, Packet pkt){
        int type = transportPkt.getType();
        switch(type){
            case 0: // SYN
                byte[] transportPktPayload = {};
                Transport replyPkt = new Transport(transportPkt.getDestPort(), transportPkt.getSrcPort(), 1, 1, 1, transportPktPayload);
                tcpMan.node.sendSegment(pkt.getDest(), pkt.getSrc(), Protocol.TRANSPORT_PKT, replyPkt.pack());

                TCPSock newSocket = new TCPSock(tcpMan);
                newSocket.destAddr = pkt.getSrc();
                newSocket.destPort = transportPkt.getSrcPort();
                newSocket.srcAddr = pkt.getDest();
                newSocket.srcPort = pkt.getSrc();

                backlogSockets.add(newSocket);
                break;
            case 1: // ACK
                if (this.state == State.SYN_SENT){
                    this.state = State.ESTABLISHED;
                }
                break;
            case 2: // FIN
                break;
            case 3: // DATA
                break;
        }
    }

    /*
     * End of socket API
     */
}
