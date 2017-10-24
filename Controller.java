package cnt5106c.p2p_file_sharing;

import java.net.Socket;
import java.net.ServerSocket;
import java.net.SocketException;
import java.io.IOException;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.PriorityQueue;
import java.util.Comparator;
import java.util.List;

// Main loop for peerProcess
class Controller implements Runnable {
	final private peerProcess peer;
	final private ExecutorService threadPool;
	final private ScheduledExecutorService unchoke;
	final private ScheduledExecutorService optUnchoke;
	final private ScheduledExecutorService monitor;
	final private ServerSocket server;
	ArrayList<PeerHandler> peerHandlers;
	List<String> interestedPeers;
	List<String> chokedPeers;
	List<PeerHandler.Requested> requested;
	List<String> preferredNeighbors;
	String optUnchokedPeerID;
	
	Controller(peerProcess peer) throws IOException {
		this.peer = peer;
		threadPool = Executors.newFixedThreadPool(10);
		unchoke = Executors.newSingleThreadScheduledExecutor();
		optUnchoke = Executors.newSingleThreadScheduledExecutor();
		monitor = Executors.newSingleThreadScheduledExecutor();
		server = new ServerSocket(peer.peerInfos.get(peer.peerID).port);
		peerHandlers = new ArrayList<PeerHandler>();
		interestedPeers = Collections.synchronizedList(new ArrayList<String>());
		chokedPeers = Collections.synchronizedList(new ArrayList<String>());
		requested = Collections.synchronizedList(new ArrayList<PeerHandler.Requested>());
		preferredNeighbors = Collections.synchronizedList(new ArrayList<String>(peer.numPrefNeighbor));
		optUnchokedPeerID = null;
	}
	
	// Listen on inbound peer connections and open a new thread for each connection
	public void run() {
		unchoke.scheduleAtFixedRate(new UnchokeHandler(peer), 0, peer.unchokeInterval, TimeUnit.SECONDS);
		optUnchoke.scheduleAtFixedRate(new OptUnchokeHandler(peer), 0, peer.optUnchokeInterval, TimeUnit.SECONDS);
		monitor.scheduleAtFixedRate(new PeerFileMonitor(peer), 0, 2, TimeUnit.SECONDS);
		
		try {
			init();
		}
		catch(IOException e) {
			System.out.println("Failed to connect to previous peers.");
			e.printStackTrace();
			try {
				shutdown();
			}
			catch(InterruptedException ex) {
				System.out.println("Failed to shutdown.");
				ex.printStackTrace();
			}
			catch(IOException ex) {
				System.out.println("Failed to close server.");
				ex.printStackTrace();
			}
			return;
		}
		catch(InterruptedException e) {
			e.printStackTrace();
			return;
		}
		
		try {
			while(true) {
				Socket inbound = server.accept();
				inbound.setSendBufferSize(1024000);
				inbound.setReceiveBufferSize(1024000);
				//inbound.setSoTimeout(25000);
				
				DataInputStream in = new DataInputStream(inbound.getInputStream());
				if(Handshake.verifyHandshakeMessage(in, null)) {
					String partnerID = String.valueOf(in.readInt());
					chokedPeers.add(partnerID);
					
					DataOutputStream out = new DataOutputStream(inbound.getOutputStream());
					Handshake.sendHandshakeMessage(out, peer.peerID);
					
					peer.logger.logTCPConnection(partnerID, Logger.Direction.CONNECT_FROM);
					PeerHandler peerHandler = new PeerHandler(peer, partnerID, inbound);
					peer.peerInfos.get(partnerID).handler = peerHandler;
					peerHandlers.add(peerHandler);
					threadPool.submit(peerHandler);
				}
				else {
					in.close();
					inbound.close();
				}
			}
		}
		catch(SocketException e) {
			System.out.println("Server closed.");
			try {
				shutdown();
			}
			catch(IOException | InterruptedException ex) {
				System.out.println("Failed to shutdown controller.");
				ex.printStackTrace();
			}
			return;
		}
		catch(IOException e) {
			System.out.println("Failed to accept connection.");
			e.printStackTrace();
			try {
				shutdown();
			}
			catch(InterruptedException ex) {
				System.out.println("Failed to shutdown.");
				ex.printStackTrace();
			}
			catch(IOException ex) {
				System.out.println("Failed to close server.");
				ex.printStackTrace();
			}
			return;
		}
		catch(InterruptedException e) {
			e.printStackTrace();
			return;
		}
	}
	
	// Initialize controller by making connections to all previous peers
	private void init() throws IOException, InterruptedException {
		for (String p : peer.previousPeersID) {
			Socket client = new Socket(peer.peerInfos.get(p).addr, peer.peerInfos.get(p).port);
			client.setSendBufferSize(1024000);
			client.setReceiveBufferSize(1024000);
			//client.setSoTimeout(25000);
			
			DataOutputStream out = new DataOutputStream(client.getOutputStream());
			Handshake.sendHandshakeMessage(out, peer.peerID);
			
			DataInputStream in = new DataInputStream(client.getInputStream());
			if(Handshake.verifyHandshakeMessage(in, p)) {
				chokedPeers.add(p);
				peer.logger.logTCPConnection(p, Logger.Direction.CONNECT_TO);
				PeerHandler peerHandler = new PeerHandler(peer, p, client);
				peer.peerInfos.get(p).handler = peerHandler;
				peerHandlers.add(peerHandler);
				threadPool.submit(peerHandler);
			}
			else {
				out.close();
				in.close();
				client.close();
			}
		}
	}
	
	// Handle handshake messages
	private static class Handshake {
		private static final String header = "P2PFILESHARINGPROJ";
		private static final byte padding[] = new byte[10];
		
		static void sendHandshakeMessage(DataOutputStream out, String peerID) throws IOException {
			out.writeBytes(header);
			out.write(padding);
			out.writeInt(Integer.parseInt(peerID));
			out.flush();
		}
		
		static boolean verifyHandshakeMessage(DataInputStream in, String partnerID) throws IOException {
			byte buffer[] = new byte[header.length()];
			in.readFully(buffer, 0, header.length());
			String str = new String(buffer, "UTF-8");
			if(!str.equals(header))
				return false;
			
			byte pad[] = new byte[padding.length];
			in.readFully(pad, 0, padding.length);
			
			if(partnerID != null) {
				if(in.readInt() != Integer.parseInt(partnerID))
					return false;
			}
			
			return true;
		}
	}
	
	// Select and unchoke preferred neighbors
	private static class UnchokeHandler implements Runnable {
		final peerProcess peer;
		final Random random;
		
		UnchokeHandler(peerProcess peer) {
			this.peer = peer;
			random = new Random();
		}
		
		public void run() {
			ArrayList<String> previous = new ArrayList<String>(peer.controller.preferredNeighbors);
			peer.controller.preferredNeighbors.clear();
			
			// Select new preferred neighbors
			if(peer.peerInfos.get(peer.peerID).hasFile) {
				synchronized(peer.controller.interestedPeers) {
					for(int i = 0; (i < peer.numPrefNeighbor) && (peer.controller.interestedPeers.size() - i > 0); i++) {
						int index = random.nextInt(peer.controller.interestedPeers.size());
						while(peer.controller.preferredNeighbors.contains(peer.controller.interestedPeers.get(index)))
							index = random.nextInt(peer.controller.interestedPeers.size());
						
						peer.controller.preferredNeighbors.add(peer.controller.interestedPeers.get(index));
					}
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
				
				synchronized(peer.controller.interestedPeers) {
					for(String p : peer.controller.interestedPeers)
						pQueue.add(p);
				}
				
				int quota = peer.numPrefNeighbor;
				while(quota > 0 && !pQueue.isEmpty()) {
					ArrayList<String> sameRate = new ArrayList<String>();
					sameRate.add(pQueue.poll());
					
					while((!pQueue.isEmpty()) && (peer.peerInfos.get(pQueue.peek()).handler.downloadRate == peer.peerInfos.get(sameRate.get(0)).handler.downloadRate))
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
			
			synchronized(peer.controller.chokedPeers) {
				// Choke previous preferred neighbors
				for(String p : previous) {
					if(!peer.controller.preferredNeighbors.contains(p)) {
						try {
							peer.peerInfos.get(p).choked = true;
							peer.controller.chokedPeers.add(p);
							Choke.sendChokeMessage(peer.peerInfos.get(p).handler.out, peer.peerInfos.get(p).handler.neighborSocket);
						}
						catch(IOException e) {
							System.out.println("Failed to send choke message to " + peer.peerInfos.get(p).ID + ".");
							e.printStackTrace();
						}
					}
				}
				
				// Unchoke preferred neighbors
				for(String p : peer.controller.preferredNeighbors) {
					if(!previous.contains(p)) {
						try {
							for(int i = 0; i < peer.controller.chokedPeers.size(); i++) {
								if(peer.controller.chokedPeers.get(i) == p) {
									peer.controller.chokedPeers.remove(i);
									break;
								}
							}
							peer.peerInfos.get(p).choked = false;
							Unchoke.sendUnchokeMessage(peer.peerInfos.get(p).handler.out, peer.peerInfos.get(p).handler.neighborSocket);
						}
						catch(IOException e) {
							System.out.println("Failed to send unchoke message to " + peer.peerInfos.get(p).ID + ".");
							e.printStackTrace();
						}
					}
				}
			}
			
			// Reset download rate counter for all peers
			for(PeerHandler ph : peer.controller.peerHandlers)
				ph.downloadRate = 0;
		}
	}
	
	// Randomly select and unchoke optimistically unchoked neighbor
	private static class OptUnchokeHandler implements Runnable {
		final peerProcess peer;
		final Random random;
		
		OptUnchokeHandler(peerProcess peer) {
			this.peer = peer;
			random = new Random();
		}
		
		public void run() {
			synchronized(peer.controller.chokedPeers) {
				if(peer.controller.chokedPeers.size() == 0)
					return;
				
				boolean existInterested = false;
				for(String p : peer.controller.chokedPeers) {
					if(peer.peerInfos.get(p).interested) {
						existInterested = true;
						break;
					}
				}
				
				int index = -1;
				while(existInterested) {
					index = random.nextInt(peer.controller.chokedPeers.size());
					if(peer.peerInfos.get(peer.controller.chokedPeers.get(index)).interested) {
						try {
							// Choke previous optimistically unchoked neighbor
							if(peer.controller.optUnchokedPeerID != null) {
								peer.peerInfos.get(peer.controller.optUnchokedPeerID).choked = true;
								peer.controller.chokedPeers.add(peer.controller.optUnchokedPeerID);
								Choke.sendChokeMessage(peer.peerInfos.get(peer.controller.optUnchokedPeerID).handler.out,
										peer.peerInfos.get(peer.controller.optUnchokedPeerID).handler.neighborSocket);
							}
							
							// Set new optimistically unchoked neighbor
							peer.controller.optUnchokedPeerID = peer.controller.chokedPeers.get(index);
							peer.logger.logChangeOptimisticallyUnchokedNeighbor(peer.controller.optUnchokedPeerID);
							
							// Unchoke optimistically unchoked neighbor
							peer.peerInfos.get(peer.controller.optUnchokedPeerID).choked = false;
							peer.controller.chokedPeers.remove(index);
							Unchoke.sendUnchokeMessage(peer.peerInfos.get(peer.controller.optUnchokedPeerID).handler.out,
									peer.peerInfos.get(peer.controller.optUnchokedPeerID).handler.neighborSocket);
						}
						catch(IOException e) {
							System.out.println("Failed to send choke or unchoke message.");
							e.printStackTrace();
						}
						break;
					}
				}
			}
		}
	}
	
	// Handle choke messages
	private static class Choke {
		static void sendChokeMessage(DataOutputStream out, Socket target) throws IOException {
			synchronized(out) {
				if(target.isClosed())
					return;
				
				out.writeInt(1);
				out.writeByte(PeerHandler.MessageType.CHOKE.value);
				out.flush();
			}
		}
	}

	// Handle unchoke messages
	private static class Unchoke {
		static void sendUnchokeMessage(DataOutputStream out, Socket target) throws IOException {
			synchronized(out) {
				if(target.isClosed())
					return;
				
				out.writeInt(1);
				out.writeByte(PeerHandler.MessageType.UNCHOKE.value);
				out.flush();
			}
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
					//System.out.println("All peers have finished download. Shutting down.");
					peer.controller.server.close();
					peer.logger.closeFile();
				}
				catch(IOException e) {
					System.out.println("Failed to shutdown controller.");
					e.printStackTrace();
					return;
				}
			}
		}
	}
	
	// Shutdown the main loop and close all connections
	void shutdown() throws InterruptedException, IOException {
		synchronized(peerHandlers) {
			for (PeerHandler ph : peerHandlers)
				ph.shutdown();
			peerHandlers = null;
		}
		
		monitor.shutdown();
		threadPool.shutdown();
		unchoke.shutdown();
		optUnchoke.shutdown();
		peer.progressBar.shutdown();
		
		if(!monitor.awaitTermination(10, TimeUnit.SECONDS)) {
			monitor.shutdownNow();
			System.out.println("Executor service monitor did not terminate in 10 secs.");
			System.out.println("May have runaway processes.");
		}
		
		if(!threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
			threadPool.shutdownNow();
			System.out.println("Executor service threadPool did not terminate in 30 secs.");
			System.out.println("May have runaway processes.");
		}
		
		if(!unchoke.awaitTermination(10, TimeUnit.SECONDS)) {
			unchoke.shutdownNow();
			System.out.println("Executor service unchoke did not terminate in 10 secs.");
			System.out.println("May have runaway processes.");
		}
		
		if(!optUnchoke.awaitTermination(10, TimeUnit.SECONDS)) {
			optUnchoke.shutdownNow();
			System.out.println("Executor service optUnchoke did not terminate in 10 secs.");
			System.out.println("May have runaway processes.");
		}
		
		if(!peer.progressBar.awaitTermination(10, TimeUnit.SECONDS)) {
			peer.progressBar.shutdownNow();
			System.out.println("Executor service progressBar did not terminate in 10 secs.");
			System.out.println("May have runaway processes.");
		}
	}
}
