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
	peerProcess.PeerInfo peerInfo;
	Socket neighborSocket;
	DataInputStream in;
	DataOutputStream out;
	private final BitfieldHandler bitfieldHandler;
	private final PieceHandler pieceHandler;
	int downloadRate;
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
		
		final byte value;
		
		MessageType(byte v) {
			value = v;
		}
		
		static MessageType typeFromByte(byte b) {
			if(b <= (byte)7)
				return MessageType.values()[b];
			else
				return null;
		}
	}
	
	PeerHandler(peerProcess peer, String partnerID, Socket neighboeSocket) throws IOException, InterruptedException {
		this.peer = peer;
		this.partnerID = partnerID;
		peerInfo = peer.peerInfos.get(partnerID);
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
		pieceHandler = new PieceHandler(peer);
		downloadRate = 0;
		shutdown = false;
	}
	
	public void run() {
		try {
			if(peer.peerInfos.get(peer.peerID).bitfield.length() != 0)
				bitfieldHandler.sendBitfieldMessage(out);
			
			int messageLength;
			byte messageType;
			
			messageLength = in.readInt();
			messageType = in.readByte();
			
			// Deal with partner's bitfield message
			if(MessageType.typeFromByte(messageType) == MessageType.BITFIELD) {
				byte bits[] = new byte[peer.pieceCount];
				in.read(bits, 0, messageLength - 1);
				peerInfo.bitfield = BitSet.valueOf(bits);
				
				if(bitfieldHandler.diff())
					Interested.sendInterestedMessage(out);
				else
					NotInterested.sendNotInterestedMessage(out);
				
				messageLength = in.readInt();
				messageType = in.readByte();
			}
			else
				peerInfo.bitfield = new BitSet(peer.pieceCount);
			
			while(!shutdown) {
				int index = -1;
				byte data[] = null;
				
				switch(MessageType.typeFromByte(messageType)) {
				case CHOKE:
					
					break;
					
				case UNCHOKE:
					
					// send request
					
					
					break;
					
				case INTERESTED:
					peerInfo.interested = true;
					peer.controller.interestedPeers.add(partnerID);
					peer.logger.logReceiveInterested(partnerID);
					break;
					
				case NOT_INTERESTED:
					peerInfo.interested = false;
					for(int i = 0; i < peer.controller.interestedPeers.size(); i++) {
						if(peer.controller.interestedPeers.get(i) == partnerID) {
							peer.controller.interestedPeers.remove(i);
							break;
						}
					}
					peer.logger.logReceiveNotInterested(partnerID);
					break;
					
				case HAVE:
					index = in.readInt();
					bitfieldHandler.setBitfield(index);
					peer.logger.logReceiveHave(partnerID, index);
					if(bitfieldHandler.diff())
						Interested.sendInterestedMessage(out);
					break;	
					
				case REQUEST:
					
					// if choked, don't send piece
					
					
					index = in.readInt();
					pieceHandler.sendPieceMessage(out, index);
					break;	
					
				case PIECE:
					index = in.readInt();
					downloadRate += (messageLength - 1);
					in.read(data, 0, messageLength - 1);
					pieceHandler.rcvPieceMessage(data, index);
					peer.logger.logDownloadPiece(partnerID, index, );
					
					
					// send have
					
					
					
					// send not interested
	
					
					
					break;
					
				default:
					System.out.println("Invalid message type.");
					break;
				}
				
				messageLength = in.readInt();
				messageType = in.readByte();
			}
			
			in.close();
			out.close();
			neighborSocket.close();
		}
		catch(IOException e) {
			System.out.println("IOException in PeerHandler run.");
			return;
		}
	}
	
	// Handle bitfield messages, maintain bitfield for partner and compare bitfield
	private class BitfieldHandler {
		peerProcess peer;
		String partnerID;
		
		BitfieldHandler(peerProcess peer, String partnerID) {
			this.peer = peer;
			this.partnerID = partnerID;
		}
		
		void sendBitfieldMessage(DataOutputStream out) throws IOException {
			byte bits[] = peer.peerInfos.get(peer.peerID).bitfield.toByteArray();
			
			out.writeInt(1 + bits.length);
			out.writeByte(MessageType.BITFIELD.value);
			out.write(bits, 0, bits.length);
			
			out.flush();
		}
		
		void setBitfield(int pos) {
			peer.peerInfos.get(partnerID).bitfield.set(pos);
		}
		
		boolean diff() {
			return !peer.peerInfos.get(peer.peerID).bitfield.equals(peer.peerInfos.get(partnerID).bitfield);
		}
		
		/*void setHasFile() {
			hasFile = true;
		}*/
	}
	
	// Handle interested messages
	private static class Interested {
		static void sendInterestedMessage(DataOutputStream out) throws IOException {
			out.writeInt(1);
			out.writeByte(MessageType.INTERESTED.value);
			out.flush();
		}
	}
	
	// Handle not interested messages
	private static class NotInterested {
		static void sendNotInterestedMessage(DataOutputStream out) throws IOException {
			out.writeInt(1);
			out.writeByte(MessageType.NOT_INTERESTED.value);
			out.flush();
		}
	}
	
	// Handle have messages
	private static class Have {
		static void sendHaveMessage(DataOutputStream out, int index) throws IOException {
			out.writeInt(1 + 4);
			out.writeByte(MessageType.HAVE.value);
			out.writeInt(index);
			out.flush();
		}
	}
	
	// Handle request messages
	private static class Request {
		static void sendRequestMessage(DataOutputStream out, int index) throws IOException {
			out.writeInt(1 + 4);
			out.writeByte(MessageType.REQUEST.value);
			out.writeInt(index);
			out.flush();
		}
	}
	
	// Handle piece messages
	private class PieceHandler {
		peerProcess peer;
		
		PieceHandler(peerProcess peer) {
			this.peer = peer;
		}
		
		void sendPieceMessage(DataOutputStream out, int index) throws IOException {
			byte data[] = peer.fileHandler.readPiece(index);
			
			out.writeInt(1 + data.length);
			out.writeByte(MessageType.PIECE.value);
			out.writeInt(index);
			out.write(data);
			out.flush();
		}
		
		int rcvPieceMessage(byte data[], int index) throws IOException {
			peer.fileHandler.writePiece(data, index);
			peer.peerInfos.get(peer.peerID).bitfield.set(index);
			
			// Check download complete
			if(peer.peerInfos.get(peer.peerID).bitfield.cardinality() == peer.pieceCount)
				peer.logger.logDownloadComplete();
			
			return peer.peerInfos.get(peer.peerID).bitfield.cardinality();
		}
	}
	
	// Close peer connection
	void shutdown() throws InterruptedException, IOException {
		shutdown = true;
	}
}
