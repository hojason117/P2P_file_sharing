package cnt5106c.p2p_file_sharing;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.BitSet;

// Handle a single peer connection
class PeerHandler implements Runnable {
	peerProcess peer;
	String partnerID;
	Socket neighborSocket;
	DataInputStream in;
	DataOutputStream out;
	BitfieldHandler bitfieldHandler;
	boolean shutdown;
	
	static enum MessageType {
		CHOKE((byte)0),	
		UNCHOKE((byte)1),
		INTERESTED((byte)2),
		NOT_INTERESTED((byte)3),
		HAVE((byte)4),
		BITFIELD((byte)5),
		REQUEST((byte)6),
		PIECE((byte)7);
		
		byte value;
		
		MessageType(byte v) {
			value = v;
		}
		
		byte value() {
			return value;
		}
	}
	
	PeerHandler(peerProcess peer, String partnerID, Socket neighboeSocket) throws IOException, InterruptedException {
		this.peer = peer;
		this.partnerID = partnerID;
		this.neighborSocket = neighboeSocket;
		try {
			in = new DataInputStream(neighborSocket.getInputStream());
			out = new DataOutputStream(neighborSocket.getOutputStream());
		}
		catch(IOException e) {
			System.out.println("Failed to initialize PeerHandler.");
			shutdown();
			throw e;
		}
		bitfieldHandler = new BitfieldHandler(peer, partnerID);
		shutdown = false;
	}
	
	public void run() {
		
		//send bitfield here
		
		
		while(!shutdown) {
			
			
			
			
			
		}
		
		
		
	}
	
	private class BitfieldHandler {
		peerProcess peer;
		String partnerID;
		
		BitfieldHandler(peerProcess peer, String partnerID) {
			this.peer = peer;
			this.partnerID = partnerID;
		}
		
		Bitfield genBitfieldMessage() {
			return new Bitfield(, MessageType.BITFIELD.value(), peer.peerInfos.get(peer.peerID).bitfield);	
		}
		
		void setBitfield(int pos) {
			peer.peerInfos.get(partnerID).bitfield.set(pos);
		}
		
		class Bitfield {
			int length;
			byte type;
			BitSet payload;
			
			Bitfield(int length, byte type, BitSet payload) {
				this.length = length;
				this.type = type;
				this.payload = payload;
			}
		}
		
		/*void setHasFile() {
			hasFile = true;
		}*/
	}
	
	// Close this peer connection
	void shutdown() throws InterruptedException, IOException {
		shutdown = true;
		neighborSocket.close();
	}
}