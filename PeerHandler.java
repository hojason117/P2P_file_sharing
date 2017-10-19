package cnt5106c.p2p_file_sharing;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.BitSet;
import java.util.Random;

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
	boolean chokedByPartner;
	boolean sentInterested;
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
		bitfieldHandler = new BitfieldHandler();
		pieceHandler = new PieceHandler();
		downloadRate = 0;
		chokedByPartner = true;
		sentInterested = false;
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
			
			while(!shutdown) {
				int index = -1;
				int req = -1;
				byte data[] = new byte[peer.pieceSize];
				
				switch(MessageType.typeFromByte(messageType)) {
				case CHOKE:
					chokedByPartner = true;
					synchronized(peer.controller.requested) {
						for(int i = 0; i < peer.controller.requested.size(); i++) {
							if(peer.controller.requested.get(i).target == partnerID) {
								peer.controller.requested.remove(i);
								break;
							}
						}
					}
					peer.logger.logChoking(partnerID);
					break;
					
				case UNCHOKE:
					chokedByPartner = false;
					peer.logger.logUnchoking(partnerID);
					
					req = bitfieldHandler.pickRequestIndex();
					
					synchronized(peer.controller.requested) {
						peer.controller.requested.add(new Requested(partnerID, req));
					}
					Request.sendRequestMessage(out, req);
					break;
					
				case INTERESTED:
					peerInfo.interested = true;
					synchronized(peer.controller.interestedPeers) {
						peer.controller.interestedPeers.add(partnerID);
					}
					peer.logger.logReceiveInterested(partnerID);
					break;
					
				case NOT_INTERESTED:
					peerInfo.interested = false;
					synchronized(peer.controller.interestedPeers) {
						for(int i = 0; i < peer.controller.interestedPeers.size(); i++) {
							if(peer.controller.interestedPeers.get(i) == partnerID) {
								peer.controller.interestedPeers.remove(i);
								break;
							}
						}
					}
					peer.logger.logReceiveNotInterested(partnerID);
					break;
					
				case HAVE:
					index = in.readInt();
					bitfieldHandler.setBitfield(index);
					peer.logger.logReceiveHave(partnerID, index);
					
					if(bitfieldHandler.want(null) && !sentInterested) {
						Interested.sendInterestedMessage(out);
						sentInterested = true;
					}
					break;
					
				case BITFIELD:
					byte bits[] = new byte[messageLength - 1];
					in.readFully(bits, 0, messageLength - 1);
					peerInfo.bitfield = BitSet.valueOf(bits);
					
					if(bitfieldHandler.want(null)) {
						Interested.sendInterestedMessage(out);
						sentInterested = true;
					}
					else {
						NotInterested.sendNotInterestedMessage(out);
						sentInterested = false;
					}
					break;
					
				case REQUEST:
					if(!peerInfo.choked) {
						index = in.readInt();
						pieceHandler.sendPieceMessage(out, index);
					}
					break;	
					
				case PIECE:
					index = in.readInt();
					downloadRate += (messageLength - 1);
					in.readFully(data, 0, messageLength - 1);
					int total = pieceHandler.rcvPieceMessage(data, index);
					peer.logger.logDownloadPiece(partnerID, index, total);
					pieceHandler.checkDownloadComplete();
					
					// Send have message to all peers
					synchronized(peer.controller.peerHandlers) {
						for(PeerHandler p : peer.controller.peerHandlers)
							Have.sendHaveMessage(p.out, index);
					}
					
					// Send not interested or another request
					if(peer.peerInfos.get(peer.peerID).hasFile) {
						synchronized(peer.controller.peerHandlers) {
							for(PeerHandler p : peer.controller.peerHandlers) {
								if(p.sentInterested) {
									NotInterested.sendNotInterestedMessage(p.out);
									p.sentInterested = false;
								}
							}
						}
					}
					else {
						synchronized(peer.controller.peerHandlers) {
							for(PeerHandler p : peer.controller.peerHandlers) {
								if(!bitfieldHandler.want(p.partnerID) && p.sentInterested) {
									NotInterested.sendNotInterestedMessage(p.out);
									p.sentInterested = false;
								}
							}
						}
						
						if(sentInterested) {
							req = bitfieldHandler.pickRequestIndex();
							synchronized(peer.controller.requested) {
								peer.controller.requested.add(new Requested(partnerID, req));
							}
							Request.sendRequestMessage(out, req);
						}
					}
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
			e.printStackTrace();
			return;
		}
	}
	
	// Handle bitfield messages, maintain bitfield for partner and compare bitfield
	private class BitfieldHandler {
		void sendBitfieldMessage(DataOutputStream out) throws IOException {
			byte bits[] = peer.peerInfos.get(peer.peerID).bitfield.toByteArray();
			
			synchronized(out) {
				out.writeInt(1 + bits.length);
				out.writeByte(MessageType.BITFIELD.value);
				out.write(bits, 0, bits.length);
				out.flush();
			}
		}
		
		void setBitfield(int pos) {
			peerInfo.bitfield.set(pos);
			if(peerInfo.bitfield.cardinality() == peer.pieceCount)
				peerInfo.hasFile = true;
		}
		
		boolean want(String targetID) {
			peerProcess.PeerInfo target;
			
			if(targetID == null)
				target = peerInfo;
			else
				target = peer.peerInfos.get(targetID);
			
			BitSet desire = (BitSet)target.bitfield.clone();
			desire.andNot(peer.peerInfos.get(peer.peerID).bitfield);
			
			return (desire.length() == 0) ? false : true;
		}
		
		int pickRequestIndex() {
			BitSet avail = (BitSet)peerInfo.bitfield.clone();
			avail.andNot(peer.peerInfos.get(peer.peerID).bitfield);
			
			Random random = new Random();
			int index = -1;
			while(!(index >= 0 && index < peer.pieceCount))
				index = avail.nextSetBit(random.nextInt(peer.pieceCount));
			
			return index;
		}
	}
	
	// Handle interested messages
	private static class Interested {
		static void sendInterestedMessage(DataOutputStream out) throws IOException {
			synchronized(out) {
				out.writeInt(1);
				out.writeByte(MessageType.INTERESTED.value);
				out.flush();
			}
		}
	}
	
	// Handle not interested messages
	private static class NotInterested {
		static void sendNotInterestedMessage(DataOutputStream out) throws IOException {
			synchronized(out) {
				out.writeInt(1);
				out.writeByte(MessageType.NOT_INTERESTED.value);
				out.flush();
			}
		}
	}
	
	// Handle have messages
	private static class Have {
		static void sendHaveMessage(DataOutputStream out, int index) throws IOException {
			synchronized(out) {
				out.writeInt(1 + 4);
				out.writeByte(MessageType.HAVE.value);
				out.writeInt(index);
				out.flush();
			}
		}
	}
	
	// Handle request messages
	private static class Request {
		static void sendRequestMessage(DataOutputStream out, int index) throws IOException {
			synchronized(out) {
				out.writeInt(1 + 4);
				out.writeByte(MessageType.REQUEST.value);
				out.writeInt(index);
				out.flush();
			}
		}
	}
	
	// Handle piece messages
	private class PieceHandler {
		void sendPieceMessage(DataOutputStream out, int index) throws IOException {
			byte data[] = peer.fileHandler.readPiece(index);
			
			synchronized(out) {
				out.writeInt(1 + data.length);
				out.writeByte(MessageType.PIECE.value);
				out.writeInt(index);
				out.write(data);
				out.flush();
			}
		}
		
		int rcvPieceMessage(byte data[], int index) throws IOException {
			peer.fileHandler.writePiece(data, index);
			peer.peerInfos.get(peer.peerID).bitfield.set(index);
			
			return peer.peerInfos.get(peer.peerID).bitfield.cardinality();
		}
		
		void checkDownloadComplete() {
			if(peer.peerInfos.get(peer.peerID).bitfield.cardinality() == peer.pieceCount) {
				peer.peerInfos.get(peer.peerID).hasFile = true;
				peer.logger.logDownloadComplete();
			}
		}
	}
	
	// Records for requested pieces
	class Requested {
		String target;
		int index;
		
		Requested(String target, int index) {
			this.target = target;
			this.index = index;
		}
	}
	
	// Close peer connection
	void shutdown() throws InterruptedException, IOException {
		shutdown = true;
	}
}
