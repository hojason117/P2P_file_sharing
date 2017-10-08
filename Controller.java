package cnt5106c.p2p_file_sharing;

import java.net.Socket;
import java.net.ServerSocket;
import java.net.SocketException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Random;
import java.util.PriorityQueue;
import java.util.Comparator;

// Main loop for peerProcess
class Controller implements Runnable {
	final private peerProcess peer;
	final private ExecutorService threadPool;
	final private ScheduledExecutorService unchoke;
	final private ScheduledExecutorService optUnchoke;
	final private ScheduledExecutorService monitor;
	final private ServerSocket server;
	private ArrayList<PeerHandler> peerHandlers;
	ArrayList<String> interestedPeers;
	ArrayList<String> chokedPeers;
	ArrayList<String> preferredNeighbors;
	String optUnchokedPeerID;
	
	Controller(peerProcess peer) throws IOException {
		this.peer = peer;
		threadPool = Executors.newFixedThreadPool(10);
		unchoke = Executors.newSingleThreadScheduledExecutor();
		optUnchoke = Executors.newSingleThreadScheduledExecutor();
		monitor = Executors.newSingleThreadScheduledExecutor();
		server = new ServerSocket(peer.peerInfos.get(peer.peerID).port);
		peerHandlers = new ArrayList<PeerHandler>();
		interestedPeers = new ArrayList<String>();
		chokedPeers = new ArrayList<String>();
		preferredNeighbors = new ArrayList<String>(peer.numPrefNeighbor);
		optUnchokedPeerID = null;
	}
	
	// Listen on inbound peer connections and open a new thread for each connection
	public void run() {
		unchoke.scheduleAtFixedRate(new UnchokeHandler(peer), peer.unchokeInterval, peer.unchokeInterval, TimeUnit.SECONDS);
		optUnchoke.scheduleAtFixedRate(new OptUnchokeHandler(peer), peer.optUnchokeInterval, peer.optUnchokeInterval, TimeUnit.SECONDS);
		monitor.scheduleAtFixedRate(new PeerFileMonitor(peer), 1, 1, TimeUnit.SECONDS);
		
		try {
			init();
		}
		catch(IOException e) {
			System.out.println("Failed to connect to previous peers.");
			try {
				shutdown();
			}
			catch(InterruptedException ex) {
				System.out.println("Failed to shutdown.");
			}
			catch(IOException ex) {
				System.out.println("Failed to close server.");
			}
			return;
		}
		catch(InterruptedException e) {
			return;
		}
		
		try {
			while(true) {
				Socket inbound = server.accept();
				inbound.setSoTimeout(20000);
				
				BufferedReader in = new BufferedReader(new InputStreamReader(inbound.getInputStream()));
				String message = in.readLine();
				
				if(message.substring(0, 18).equals(Handshake.header)) {
					String partnerID = String.valueOf(ByteBuffer.allocate(4).put(message.substring(28, 32).getBytes()).getInt());
					
					PeerHandler peerHandler = new PeerHandler(peer, partnerID, inbound);
					peer.peerInfos.get(partnerID).handler = peerHandler;
					peerHandlers.add(peerHandler);
					threadPool.submit(peerHandler);
					peer.logger.logTCPConnection(partnerID, Logger.Direction.CONNECT_FROM);
					
					DataOutputStream out = new DataOutputStream(inbound.getOutputStream());
					out.writeBytes(Handshake.genHandshakeMessage(Integer.parseInt(peer.peerID)));
					
					in.close();
					out.close();
				}
				else
					in.close();
					inbound.close();;
			}
		}
		catch(SocketException e) {
			return;
		}
		catch(IOException e) {
			System.out.println("Failed to accept connection.");
			try {
				shutdown();
			}
			catch(InterruptedException ex) {
				System.out.println("Failed to shutdown.");
			}
			catch(IOException ex) {
				System.out.println("Failed to close server.");
			}
			return;
		}
		catch(InterruptedException e) {
			return;
		}
	}
	
	// Initialize controller by making connections to all previous peers
	private void init() throws IOException, InterruptedException {
		for (String p : peer.previousPeersID) {
			Socket client = new Socket(peer.peerInfos.get(p).addr, peer.peerInfos.get(p).port);
			client.setSoTimeout(20000);
			
			DataOutputStream out = new DataOutputStream(client.getOutputStream());
			out.writeBytes(Handshake.genHandshakeMessage(Integer.parseInt(peer.peerID)));
			out.flush();
			
			BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
			if(Handshake.verifyHandshakeMessage(in.readLine(), Integer.parseInt(p))) {
				PeerHandler peerHandler = new PeerHandler(peer, p, client);
				peer.peerInfos.get(p).handler = peerHandler;
				peerHandlers.add(peerHandler);
				threadPool.submit(peerHandler);
				peer.logger.logTCPConnection(p, Logger.Direction.CONNECT_TO);
			}
			
			out.close();
			in.close();
		}
	}
	
	// Handle handshake messages
	private static class Handshake {
		private static final String header = "P2PFILESHARINGPROJ";
		private static final byte padding[] = new byte[10];
		
		static String genHandshakeMessage(int peerID) throws IOException {
			ByteArrayOutputStream m = new ByteArrayOutputStream();
			m.write(header.getBytes());
			m.write(padding);
			m.write(ByteBuffer.allocate(4).putInt(peerID).array());
			
			return m.toString();
		}
		
		static boolean verifyHandshakeMessage(String message, int partnerID) throws IOException {
			if(!message.substring(0, 18).equals(header))
				return false;
			
			if(ByteBuffer.allocate(4).put(message.substring(28, 32).getBytes()).getInt() != partnerID)
				return false;
			
			return true;
		}
	}
	
	// Select and unchoke preferred neighbors
	private static class UnchokeHandler implements Runnable {
		peerProcess peer;
		Random random;
		
		UnchokeHandler(peerProcess peer) {
			this.peer = peer;
			random = new Random();
		}
		
		public void run() {
			synchronized(peer.controller.chokedPeers) {
				ArrayList<String> previous = new ArrayList<String>(peer.controller.preferredNeighbors);
				peer.controller.preferredNeighbors.clear();
				
				// Select new preferred neighbors
				if(peer.peerInfos.get(peer.peerID).hasFile) {
					for(int i = 0; (i < peer.numPrefNeighbor) && (peer.controller.interestedPeers.size() - i > 0); i++) {
						int index = random.nextInt(peer.controller.interestedPeers.size());
						while(peer.controller.preferredNeighbors.contains(peer.controller.interestedPeers.get(index))) 
							index = random.nextInt(peer.controller.interestedPeers.size());
						
						peer.controller.preferredNeighbors.add(peer.controller.interestedPeers.get(index));
					}
				}
				else {
					class DownloadRateComparator implements Comparator<String> {
						public int compare(String p1, String p2) {
							return Integer.compare(peer.peerInfos.get(p1).handler.downloadRate, peer.peerInfos.get(p2).handler.downloadRate);
						}
					}
					DownloadRateComparator compare = new DownloadRateComparator();
					PriorityQueue<String> pQueue = new PriorityQueue<String>(10, compare.reversed());
					
					for(String p : peer.controller.interestedPeers)
						pQueue.add(p);
					
					int quota = peer.numPrefNeighbor;
					while(quota > 0 && !pQueue.isEmpty()) {
						ArrayList<String> sameRate = new ArrayList<String>();
						sameRate.add(pQueue.poll());
						while(peer.peerInfos.get(pQueue.peek()).handler.downloadRate == peer.peerInfos.get(sameRate.get(0)).handler.downloadRate)
							sameRate.add(pQueue.poll());
						
						if(sameRate.size() <= quota) {
							for(String p : sameRate)
								peer.controller.preferredNeighbors.add(p);
							quota -= sameRate.size();
						}
						else {
							for(int i = 0; i < quota; i++) {
								int index = random.nextInt(sameRate.size());
								peer.controller.preferredNeighbors.add(sameRate.get(index));
								sameRate.remove(index);
							}
							quota = 0;
						}
					}
				}
				
				peer.logger.logChangePreferredNeighbor(peer.controller.preferredNeighbors);
				
				// Choke previous preferred neighbors
				for(String p : previous) {
					if(!peer.controller.preferredNeighbors.contains(p)) {
						try {
							Choke.sendChokeMessage(peer.peerInfos.get(p).handler.out);
							peer.controller.chokedPeers.add(p);
						}
						catch(IOException e) {
							System.out.println("Failed to send choke message.");
						}
					}
				}
				
				// Unchoke preferred neighbors
				for(String p : peer.controller.preferredNeighbors) {
					if(!previous.contains(p)) {
						try {
							Unchoke.sendUnchokeMessage(peer.peerInfos.get(p).handler.out);
							for(int i = 0; i < peer.controller.chokedPeers.size(); i++) {
								if(peer.controller.chokedPeers.get(i) == p) {
									peer.controller.chokedPeers.remove(i);
									break;
								}
							}
						}
						catch(IOException e) {
							System.out.println("Failed to send unchoke message.");
						}
					}
				}
				
				// Reset download rate counter for all peers
				for(PeerHandler ph : peer.controller.peerHandlers)
					ph.downloadRate = 0;
			}
		}
	}
	
	// Randomly select and unchoke optimistically unchoked neighbor
	private static class OptUnchokeHandler implements Runnable {
		peerProcess peer;
		Random random;
		
		OptUnchokeHandler(peerProcess peer) {
			this.peer = peer;
			random = new Random();
		}
		
		public void run() {
			synchronized(peer.controller.chokedPeers) {
				int index;
				while(true) {
					index = random.nextInt(peer.controller.chokedPeers.size());
					if(peer.peerInfos.get(peer.controller.chokedPeers.get(index)).interested) {
						try {
							// Choke previous optimistically unchoked neighbor
							Choke.sendChokeMessage(peer.peerInfos.get(peer.controller.optUnchokedPeerID).handler.out);
							peer.peerInfos.get(peer.controller.optUnchokedPeerID).choked = true;
							peer.controller.chokedPeers.add(peer.controller.optUnchokedPeerID);
							
							// Set new optimistically unchoked neighbor
							peer.controller.optUnchokedPeerID = peer.controller.chokedPeers.get(index);
							peer.logger.logChangeOptimisticallyUnchokedNeighbor(peer.controller.optUnchokedPeerID);
							
							// Unchoke optimistically unchoked neighbor
							Unchoke.sendUnchokeMessage(peer.peerInfos.get(peer.controller.optUnchokedPeerID).handler.out);
							peer.peerInfos.get(peer.controller.optUnchokedPeerID).choked = false;
							peer.controller.chokedPeers.remove(index);
						}
						catch(IOException e) {
							System.out.println("Failed to send choke or unchoke message.");
						}
						
						break;
					}
				}
			}
		}
	}
	
	// Handle choke messages
	private static class Choke {
		static void sendChokeMessage(DataOutputStream out) throws IOException {
			out.writeInt(1);
			out.writeByte(PeerHandler.MessageType.CHOKE.value);
			out.flush();
		}
	}

	// Handle unchoke messages
	private static class Unchoke {
		static void sendUnchokeMessage(DataOutputStream out) throws IOException {
			out.writeInt(1);
			out.writeByte(PeerHandler.MessageType.UNCHOKE.value);
			out.flush();
		}
	}
	
	// Monitoring whether all peers have completed download, shutdown controller if all peers are done
	private static class PeerFileMonitor implements Runnable {
		final peerProcess peer;
		
		PeerFileMonitor(peerProcess peer) {
			this.peer = peer;
		}
		
		public void run() {
			boolean allPeersFinished = true;
			
			for(String id : peer.peerIDs) {
				if(!peer.peerInfos.get(id).hasFile) {
					allPeersFinished = false;
					break;
				}
			}
			
			if(allPeersFinished) {
				try {
					peer.controller.shutdown();
				}
				catch(IOException | InterruptedException e) {
					System.out.println("Failed to shutdown controller.");
					return;
				}
			}
		}
	}
	
	// Shutdown the main loop and close all connections
	void shutdown() throws InterruptedException, IOException {
		for (PeerHandler ph : peerHandlers)
			ph.shutdown();
		
		threadPool.shutdown();
		if(threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
			threadPool.shutdownNow();
			System.out.println("Executor service did not terminate in 30 secs.");
			System.out.println("May have runaway processes.");
		}
		
		monitor.shutdown();
		if(monitor.awaitTermination(30, TimeUnit.SECONDS)) {
			monitor.shutdownNow();
			System.out.println("Executor service did not terminate in 30 secs.");
			System.out.println("May have runaway processes.");
		}
		
		server.close();
	}
}
