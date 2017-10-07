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

// Main loop for peerProcess
class Controller implements Runnable {
	final private peerProcess peer;
	final private ExecutorService threadPool;
	final private ScheduledExecutorService monitor;
	final private ServerSocket server;
	private ArrayList<PeerHandler> peerHandlers;
	
	Controller(peerProcess peer) throws IOException {
		this.peer = peer;
		threadPool = Executors.newFixedThreadPool(10);
		monitor = Executors.newSingleThreadScheduledExecutor();
		server = new ServerSocket(peer.peerInfos.get(peer.peerID).port);
		peerHandlers = new ArrayList<PeerHandler>();
	}
	
	// Listen on inbound peer connections and open a new thread for each connection
	public void run() {
		monitor.scheduleAtFixedRate(new PeerFileMonitor(this), 1, 1, TimeUnit.SECONDS);
		
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
				//inbound.setSoTimeout(10000);
				
				BufferedReader in = new BufferedReader(new InputStreamReader(inbound.getInputStream()));
				String message = in.readLine();
				
				if(message.substring(0, 18).equals(Handshake.header)) {
					PeerHandler peerHandler = new PeerHandler(peer, String.valueOf(ByteBuffer.allocate(4).put(message.substring(28, 32).getBytes()).getInt()), inbound);
					peerHandlers.add(peerHandler);
					threadPool.submit(peerHandler);
					peer.logger.logTCPConnection(String.valueOf(ByteBuffer.allocate(4).put(message.substring(28, 32).getBytes()).getInt()), Logger.Direction.CONNECT_FROM);
					
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
			//client.setSoTimeout(10000);
			
			DataOutputStream out = new DataOutputStream(client.getOutputStream());
			out.writeBytes(Handshake.genHandshakeMessage(Integer.parseInt(peer.peerID)));
			
			BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
			if(Handshake.verifyHandshakeMessage(in.readLine(), Integer.parseInt(p))) {
				PeerHandler peerHandler = new PeerHandler(peer, p, client);
				peerHandlers.add(peerHandler);
				threadPool.submit(peerHandler);
				peer.logger.logTCPConnection(p, Logger.Direction.CONNECT_TO);
			}
			
			out.close();
			in.close();
		}
	}
	
	// Handshake messages
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
	
	// Monitoring whether all peers have completed download, shutdown controller if all peers are done
	private static class PeerFileMonitor implements Runnable {
		final Controller controller;
		
		PeerFileMonitor(Controller controller) {
			this.controller = controller;
		}
		
		public void run() {
			boolean allPeersFinished = true;
			
			for(String id : controller.peer.peerIDs) {
				if(!controller.peer.peerInfos.get(id).hasFile) {
					allPeersFinished = false;
					break;
				}
			}
			
			if(allPeersFinished) {
				try {
					controller.shutdown();
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
