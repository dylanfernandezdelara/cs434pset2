import java.util.ArrayList;

/**
 * <p>Title: CPSC 433/533 Programming Assignment</p>
 *
 * <p>Description: Fishnet TCP manager</p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 *
 * <p>Company: Yale University</p>
 *
 * @author Hao Wang
 * @version 1.0
 */
public class TCPManager {
    public Node node;
    public int addr;
    private Manager manager;
    public ArrayList<TCPSock> tcpSocksInUse; 

    private static final byte dummy[] = new byte[0];

    public TCPManager(Node node, int addr, Manager manager) {
        this.node = node;
        this.addr = addr;
        this.manager = manager;
        this.tcpSocksInUse = new ArrayList<TCPSock>();
    }

    /**
     * Start this TCP manager
     */
    public void start() {

    }

    /*
     * Begin socket API
     */

    /**
     * Create a socket
     *
     * @return TCPSock the newly created socket, which is not yet bound to
     *                 a local port
     */
    public TCPSock socket() {
        TCPSock tcpSock = new TCPSock(this);
        return tcpSock;
    }

    public int findOpenPort(int portNumber) {
        for (int i = 0; i < tcpSocksInUse.size(); i++){
            if (tcpSocksInUse.get(i).srcPort == portNumber){
                return -1;
            }
        }
        return 0;
    }

    public void onReceive(Packet packet){
        Transport TRANSPORTpkt =  Transport.unpack(packet.getPayload());
        int destPort = TRANSPORTpkt.getDestPort();
        int destAddr = packet.getDest();
        int srcPort = TRANSPORTpkt.getSrcPort();
        int srcAddr = packet.getSrc();

        for (int i = 0; i < tcpSocksInUse.size(); i++){
            TCPSock tempSock = tcpSocksInUse.get(i);
            if (tempSock.state == TCPSock.State.ESTABLISHED && (tempSock.destAddr == srcAddr) && (tempSock.destPort == srcPort)
                    && (tempSock.srcAddr == destAddr) && (tempSock.srcPort == destPort)){
                tempSock.onReceive(TRANSPORTpkt, packet);
                return;
            }
        }
        
        for (int i = 0; i < tcpSocksInUse.size(); i++){
            TCPSock tempSock = tcpSocksInUse.get(i);
            if (tempSock.state == TCPSock.State.LISTEN && (tempSock.srcPort == destPort) ){
                tempSock.onReceive(TRANSPORTpkt, packet);
                return;
            }
        }
    }

    /*
     * End Socket API
     */
}
