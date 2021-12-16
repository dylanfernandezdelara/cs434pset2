import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.rmi.server.RemoteObjectInvocationHandler;
import java.lang.reflect.Method;
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
    // Transport mostRecentlySentPkt;
    public int srcPort;
    public int destPort;
    public int srcAddr;
    public int destAddr;

    // public int ccAlgorithm; // 0 is Reno, 1 is Cubic

    public boolean rcvFinAck;

    public int lastACKd;

    public int sendbase;
    public int sendMax;
    public int writePointer;

    public int bufferSz;
    public int writeIndex;

    public byte[] socketBuffer;
    public boolean socketType; // 0 is receive, // 1 is send

    // receiving socket
    int rReadPointer;
    int rWritePointer;

    // windows
    int rwnd; // amount of space receiving buffer has available
    int swnd; // bytes sent but not acked

    // max window size for sender
    int cwnd; // window of bytes I am allowed to send
    
    int currPendingConnections;
    public ArrayList<TCPSock> backlogSockets;

    public int nextseq;
    
    public long startOfTimedWait;

    // int currDuplicateACKs;

    // TCP socket states
    public static enum State {
        // protocol states
        CLOSED,
        LISTEN,
        SYN_SENT,
        ESTABLISHED,
        FIN_SENT,
        SHUTDOWN // close requested, FIN not sent (due to unsent data in queue)
    }
    public State state;

    public TCPSock(TCPManager tcpMan) {
        this.tcpMan = tcpMan;
        this.currPendingConnections = 0;
        this.backlogSockets = new ArrayList<TCPSock>();
        this.bufferSz = 100;
        this.socketBuffer = new byte[bufferSz];

        this.sendbase = 0;
        this.writePointer = 0;
        this.lastACKd = 0;

        this.rReadPointer = 0;
        this.rWritePointer = 0;
        
        this.rwnd = 0;
        this.cwnd = 50;

        this.rcvFinAck = false;

        this.nextseq = 0;

        // this.currDuplicateACKs = 0;

        // this.ccAlgorithm = 0; //default is Reno

        // this.mostRecentlySentPkt = null;

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
        tcpMan.tcpSocksInUse.add(firstOnQueue);
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
        return (state == State.SHUTDOWN || state == State.FIN_SENT);
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
        Transport transportPkt = new Transport(this.srcPort, destPort, 0, 1, 0, transportPktPayload);
        tcpMan.node.sendSegment(tcpMan.addr, destAddr, Protocol.TRANSPORT_PKT, transportPkt.pack());
        this.state = State.SYN_SENT;
        this.destAddr = destAddr;
        this.destPort = destPort;
        this.srcAddr = tcpMan.addr;
        tcpMan.tcpSocksInUse.add(this);
        //System.console().writer().println("S");
        //this.tcpMan.node.logOutput("SENDING SYN TO CONNECT");
        // add timer here at some point ...
        return 0;
    }

    /**
     * Initiate closure of a connection (graceful shutdown)
     */
    public void close() {
        // waits for all data to be sent then sends the fin
        // this.state = State.CLOSED;
        this.state = State.SHUTDOWN; // state transfers to CLOSED once wp == sendmax == sendbase
        tcpMan.node.logOutput("wp: " + writePointer + " sendmax: " + sendMax + " sendbase: " + sendbase + "\n");
        if ( (writePointer == sendMax) && (sendMax == sendbase) ){
            tcpMan.node.logOutput("SWITCHING STATE TO CLOSED");
            this.state = State.CLOSED;
            sendFinRT();
        } 
    }

    /**
     * Release a connection immediately (abortive shutdown)
     */
    public void release() {
        // sends fin immediately
        sendFinRT();
    }

    public void sendFinRT(){
        byte [] emptyPayload = {};
        Transport pkt = new Transport(this.srcPort, destPort, 2, 1, 99, emptyPayload);
        if (rcvFinAck){
            this.state = State.CLOSED;
            return;
        }
        try {
            //tcpMan.node.logOutput("about to send segment! ");
            tcpMan.node.sendSegment(srcAddr, destAddr, Protocol.TRANSPORT_PKT, pkt.pack());
            this.state = State.FIN_SENT;
            //System.console().writer().println(".");
            Method method = Callback.getMethod("sendFinRT", this, null);
	        Callback cb = new Callback(method, this, null);
            this.tcpMan.manager.addTimer(this.tcpMan.node.getAddr(), 10000, cb);
        }
        catch(Exception e) {
            System.err.println("Failed to add callback to sendFinRT");
        }
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
     */ // write from parameter buf into socket buffer
    public int write(byte[] buf, int pos, int len) {
        if (this.state == State.SYN_SENT){
            return 0;
        }
        //tcpMan.node.logOutput(String.valueOf(len));
        int bytesWritten = 0;
        int remainingBufferSpace = bufferSz - ( (writePointer - sendbase) % bufferSz);
        //tcpMan.node.logOutput("WRITE POINTER " + writePointer + " SENDBASE " + sendbase
        //+ " POS " + pos + " LEN " + len);
        //tcpMan.node.logOutput("REMAINING BUFFER SPACE PREWRITE " + String.valueOf(remainingBufferSpace));
        int iterator = 0;
        while (iterator < len && remainingBufferSpace > 0){
                socketBuffer[writePointer % bufferSz] = buf[pos];
                //tcpMan.node.logOutput("entering buf " + socketBuffer[writePointer % bufferSz]);
                bytesWritten++;
                pos++;
                writePointer++;
                iterator++;
                remainingBufferSpace--;
        }
        //if (remainingBufferSpace != 0) tcpMan.node.logOutput("REMAINING BUFFER SPACE POSTWRITE " + String.valueOf(remainingBufferSpace));
        
        transmitData();

        //tcpMan.node.logOutput("BYTES WRITTEN: " + String.valueOf(bytesWritten));        
        return bytesWritten;
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
     */ // read from socket buffer
    public int read(byte[] buf, int pos, int len) {
        int bytesRead = 0;
        int iterator = 0;
        //tcpMan.node.logOutput("WRITE POINTER VAL "+String.valueOf(rWritePointer));
        while (iterator < len && rReadPointer < rWritePointer){
            buf[pos] = socketBuffer[rReadPointer % bufferSz];
            //tcpMan.node.logOutput("read to buf result " + buf[pos]);
            rReadPointer++;
            bytesRead++;
            pos++;
            iterator++;
        }
        return bytesRead;
    }

    public void transmitData(){
        // int bytesForEachPacket = 10;
        // int indexOfDataSent = sendbase;
        
        // while(indexOfDataSent < sendMax) {
        //     byte[] nextPktPayload = new byte[10];
        //     int startPoint = indexOfDataSent;
        //     for (int i = 0; i < bytesForEachPacket; i++){
        //         indexOfDataSent+=i;
        //         nextPktPayload[i] = socketBuffer[indexOfDataSent];
        //     }
        //     Transport nextPkt = new Transport(srcPort, destPort, 3, 999, startPoint, nextPktPayload); // window size doesn't matter for data sent
        //     tcpMan.node.sendSegment(srcAddr, destAddr, Protocol.TRANSPORT_PKT, nextPkt.pack());
        // } 
        
        //v2
        // int bytesForEachPacket = 10;
        // int indexOfDataSent = sendMax;
        // this.swnd = sendMax - sendbase;

        // while(indexOfDataSent < rwnd - swnd) {
        //     byte[] nextPktPayload = new byte[10];
        //     int startPoint = indexOfDataSent;
        //     for (int i = 0; i < bytesForEachPacket; i++){
        //         indexOfDataSent+=i;
        //         nextPktPayload[i] = socketBuffer[indexOfDataSent];
        //     }
        //     Transport nextPkt = new Transport(srcPort, destPort, 3, 999, startPoint, nextPktPayload); // window size doesn't matter for data sent
        //     tcpMan.node.sendSegment(srcAddr, destAddr, Protocol.TRANSPORT_PKT, nextPkt.pack());
        // } 

        // int bytesForEachPacket = 10;
        // int indexOfDataSent = sendMax;
        // this.swnd = sendMax - sendbase;

        // while(sendMax < sendbase + cwnd && sendMax < sendbase + rwnd && sendMax < writePointer) {
        //     byte[] nextPktPayload = new byte[10];
        //     int startPoint = indexOfDataSent;
        //     for (int i = 0; i < bytesForEachPacket; i++){
        //         indexOfDataSent+=i;
        //         nextPktPayload[i] = socketBuffer[indexOfDataSent];
        //     }
        //     Transport nextPkt = new Transport(srcPort, destPort, 3, 999, startPoint, nextPktPayload); // window size doesn't matter for data sent
        //     tcpMan.node.sendSegment(srcAddr, destAddr, Protocol.TRANSPORT_PKT, nextPkt.pack());
        // } 
        
        //v4
        // int bytesForEachPacket = 10;
        int indexOfDataSent = sendMax;
        this.swnd = sendMax - sendbase;
        //this.tcpMan.node.logOutput("sendbase + cwnd " + String.valueOf(sendbase + cwnd));
        while(sendMax < sendbase + cwnd && sendMax < sendbase + rwnd && sendMax < writePointer) {
            int minFlowReceiver = sendMax + rwnd;
            if (minFlowReceiver > writePointer) {
                minFlowReceiver = (writePointer - sendMax) % bufferSz;
            }
            else {
                minFlowReceiver = rwnd;
            }

            int minPktSize = Math.min(Math.min(cwnd - sendMax, minFlowReceiver), Math.min(Transport.MAX_PACKET_SIZE, writePointer - sendMax));
            //tcpMan.node.logOutput("rwnd " + rwnd + " cwnd " + cwnd + " write pointer " + String.valueOf(writePointer) + " sendbase " + 
                //String.valueOf(sendbase) + " sendmax " + String.valueOf(sendMax));
            byte[] nextPktPayload = new byte[minPktSize];
            if (minPktSize == 0) return;
            int startPoint = indexOfDataSent;
            for (int i = 0; i < minPktSize; i++){
                nextPktPayload[i] = socketBuffer[indexOfDataSent % bufferSz];
                //tcpMan.node.logOutput("entering buf " + socketBuffer[indexOfDataSent % bufferSz]);
                indexOfDataSent++;
            }
            sendMax = indexOfDataSent;

            //tcpMan.node.logOutput("length of payload " + nextPktPayload.length);
            Transport nextPkt = new Transport(srcPort, destPort, 3, 999, startPoint, nextPktPayload); // window size doesn't matter for data sent
            //tcpMan.node.logOutput("seq outgoing packet " + startPoint);
            
            sendDataRT(nextPkt);
            //tcpMan.node.sendSegment(srcAddr, destAddr, Protocol.TRANSPORT_PKT, nextPkt.pack());
        } 
    }

    public void sendDataRT(Transport pkt){
        //tcpMan.node.logOutput("sendbase is: " + sendbase + "\n" + "seqnum + payload is :" + pkt.getSeqNum() + pkt.getPayload().length + "\n\n");
        if (sendbase >= pkt.getSeqNum() + pkt.getPayload().length){
            return;
        }
        try {
            //tcpMan.node.logOutput("about to send segment! ");
            // this.mostRecentlySentPkt = pkt;
            tcpMan.node.sendSegment(srcAddr, destAddr, Protocol.TRANSPORT_PKT, pkt.pack());
            if ((writePointer == sendbase) && (sendbase == sendMax) && (this.state == State.SHUTDOWN)){
                this.state = State.CLOSED;
                rcvFinAck = true;
                return;
            }
            //System.console().writer().println(".");
            String[] paramTypes = { "Transport" };
            Object[] params = { pkt };
            Method method = Callback.getMethod("sendDataRT", this, paramTypes);
	        Callback cb = new Callback(method, this, params);
            this.tcpMan.manager.addTimer(this.tcpMan.node.getAddr(), 10000, cb);
        }
        catch(Exception e) {
            System.err.println("Failed to add callback to sendDataRT");
        }
    }

    public void sendACK_RT(Transport replyAck, Packet pkt){
        if (nextseq > replyAck.getSeqNum() + replyAck.getPayload().length){
            return;
        }
        try {
            //tcpMan.node.logOutput("about to send segment! ");
            // this.mostRecentlySentPkt = pkt;
            tcpMan.node.sendSegment(pkt.getDest(), pkt.getSrc(), Protocol.TRANSPORT_PKT, replyAck.pack());
            //System.console().writer().println(".");
            String[] paramTypes = { "Transport", "Packet" };
            Object[] params = {replyAck, pkt};
            Method method = Callback.getMethod("sendACK_RT", this, paramTypes);
	        Callback cb = new Callback(method, this, params);
            this.tcpMan.manager.addTimer(this.tcpMan.node.getAddr(), 10000, cb);
        }
        catch(Exception e) {
            System.err.println("Failed to add callback to sendACK_RT");
        }
    }

    public void writeToReceiveBuffer(byte[] payload){
        for (int i = 0; i < payload.length; i++){
            socketBuffer[rWritePointer % bufferSz] = payload[i];
            //tcpMan.node.logOutput("write to buf result " + socketBuffer[rWritePointer % bufferSz]);
            rWritePointer++;
        }
        
    }

    public void setSendMax(int new_rwnd){
        if (sendMax == writePointer){
            return; // sendMax is unchanged
        }
        else if (writePointer - sendbase < new_rwnd){
            sendMax = writePointer;
        }
        else if (writePointer - sendbase > new_rwnd){
            int swnd = sendMax - sendbase;
            sendMax += (new_rwnd - swnd);
        }
    }

    public void timedWaitRT(Transport pkt){
        if (tcpMan.manager.now() - startOfTimedWait > 10000){
            this.state = State.CLOSED;
            return;
        }
        try {
            //tcpMan.node.logOutput("about to send segment! ");
            tcpMan.node.sendSegment(srcAddr, destAddr, Protocol.TRANSPORT_PKT, pkt.pack());
            this.state = State.CLOSED;
            //System.console().writer().println(".");
            String[] paramTypes = { "Transport" };
            Object[] params = { pkt };
            Method method = Callback.getMethod("timedWaitRT", this, paramTypes);
	        Callback cb = new Callback(method, this, params);
            this.tcpMan.manager.addTimer(this.tcpMan.node.getAddr(), 1000, cb);
        }
        catch(Exception e) {
            System.err.println("Failed to add callback to timedWaitRT");
        }
    }


    public void onReceive(Transport transportPkt, Packet pkt){
        int type = transportPkt.getType();
        switch(type){
            case 0: // SYN
                //System.console().writer().println("S");
                byte[] transportPktPayload = {};
                Transport replyPkt = new Transport(transportPkt.getDestPort(), transportPkt.getSrcPort(), 1, this.bufferSz, 0, transportPktPayload);
                tcpMan.node.sendSegment(pkt.getDest(), pkt.getSrc(), Protocol.TRANSPORT_PKT, replyPkt.pack());

                TCPSock newSocket = new TCPSock(tcpMan);
                newSocket.destAddr = pkt.getSrc();
                newSocket.destPort = transportPkt.getSrcPort();
                newSocket.srcAddr = pkt.getDest();
                newSocket.srcPort = transportPkt.getDestPort();

                backlogSockets.add(newSocket);
                //this.tcpMan.node.logOutput("SYN RECEIVED. ADDED SOCK TO BACKLOG");
                break;

            case 1: // ACK
                //System.console().writer().println(".");
                //this.tcpMan.node.logOutput("SOME ACK RECEIVED");
                if (this.state == State.SYN_SENT && pkt.getSeq() == 0){
                    //setSendMax(transportPkt.getWindow());
                    //this.sendMax = transportPkt.getWindow();
                    this.sendbase = transportPkt.getSeqNum();
                    this.cwnd = sendbase + cwnd;
                    this.rwnd = transportPkt.getWindow();
                    this.state = State.ESTABLISHED;
                    //this.tcpMan.node.logOutput("INITIAL CONNECTION SEQ WANTED " + String.valueOf(transportPkt.getSeqNum()));
                    transmitData();
                    //this.tcpMan.node.logOutput("CONNECTION ESTABLISHED. ACK RECEIVED");
                }
                else if (this.state == State.ESTABLISHED || this.state == State.SHUTDOWN){
                    this.rwnd = transportPkt.getWindow();
                    int change = transportPkt.getSeqNum() - sendbase;
                    this.sendbase = transportPkt.getSeqNum();
                    this.cwnd = sendbase + change;
                    // setSendMax(transportPkt.getWindow());
                    //this.tcpMan.node.logOutput("got your ACK, my sendbase is now " + String.valueOf(transportPkt.getSeqNum()));
                    //this.tcpMan.node.logOutput("my sendmax is now " + String.valueOf(sendMax));
                    //this.tcpMan.node.logOutput("wp is now " + String.valueOf(writePointer));
                //     this.currDuplicateACKs++;
                //     if (ccAlgorithm == 0){
                //         Reno_CC_Algorithm(change);
                //     }
                //     else if (ccAlgorithm == 1){
                //         Cubic_CC_Algorithm(change);
                //     }
                     transmitData();
                 }
                else if (this.state == State.FIN_SENT){
                    this.state = State.CLOSED;
                    return;
                }
                break;

            case 2: // FIN
                //System.console().writer().println("F");
                byte[] emptyClosePayload = {};
                Transport closeAck = new Transport(transportPkt.getDestPort(), transportPkt.getSrcPort(), 1, 0, 99, emptyClosePayload);
                startOfTimedWait = this.tcpMan.manager.now();
                //this.state = State.CLOSED;
                timedWaitRT(closeAck);
                break;

            case 3: // DATA
                if (transportPkt.getSeqNum() != nextseq) break;
                byte[] dataToWrite = transportPkt.getPayload();
                //tcpMan.node.logOutput("LENGTH OF DATA RECEIVED " + String.valueOf(dataToWrite.length));
                writeToReceiveBuffer(dataToWrite);
                byte[] emptyAckPayload = {};
                int remainingReceivingBufferSz = bufferSz - ( (rWritePointer - rReadPointer) % bufferSz);
                int returnedSeq = transportPkt.getSeqNum() + transportPkt.getPayload().length;
                // tcpMan.node.logOutput("RETURNED SEQ "+ returnedSeq + "\n");
                nextseq = returnedSeq;
                Transport replyAck = new Transport(transportPkt.getDestPort(), transportPkt.getSrcPort(), 1, remainingReceivingBufferSz, returnedSeq, emptyAckPayload);
                sendACK_RT(replyAck, pkt);
                // tcpMan.node.sendSegment(pkt.getDest(), pkt.getSrc(), Protocol.TRANSPORT_PKT, replyAck.pack());
                //this.tcpMan.node.logOutput("got DATA, next incoming should be " + String.valueOf(returnedSeq));
                //this.tcpMan.node.logOutput("remaining rec buf sz is " + String.valueOf(remainingReceivingBufferSz));
                break;
        }
    }

    // public void set_cc_algorithm(int type){
    //     if (type == 0){
    //         ccAlgorithm = 0;
    //     }
    //     else if (type == 1){
    //         ccAlgorithm = 1;
    //     }
    // }

    // public void Reno_CC_Algorithm(int bytesAcked){
    //     Packet mss = new Packet(destAddr, srcAddr, 1, Protocol.TRANSPORT_PKT, 0, null);
    //     byte [] mss_bytes = mss.pack();
    //     int sz_mss = mss_bytes.length;
    //     if (this.currDuplicateACKs == 3){
    //         currDuplicateACKs = 0;
    //         this.cwnd = cwnd / 2;
    //     }
    //     else {
    //         // cwnd += bytes_acked/cwnd * MSS
    //         cwnd += (bytesAcked / cwnd * sz_mss);
    //     }
    // }

    // public void Cubic_CC_Algorithm(int bytesAcked){
    //     // unfortunately, did not have time to complete Cubic
    //     // this pset was exceptionally difficult, but I enjoyed the challenge. Given more time, this pset would have been even better.
    // }

    /*
     * End of socket API
     */
}
