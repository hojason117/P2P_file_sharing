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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

class Controller implements Runnable {
	private peerProcess peer;
	private ExecutorService threadPool;
	private ServerSocket server;
	private ArrayList<PeerHandler> peerHandlers;
	
	Controller(peerProcess peer) throws IOException {
		this.peer = peer;
		threadPool = Executors.newFixedThreadPool(10);
		server = new ServerSocket(peer.port);
		peerHandlers = new ArrayList<PeerHandler>();
	}
	
	public void run() {
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
		
		try {
			while(true) {
				Socket inbound = server.accept();
				
				
				
				
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
	}
	
	private void init() throws IOException {
		for(int i = 0; i < peer.initialNeighborsID.size(); i++) {
			Socket client = new Socket(peer.initialNeighborsAddr.get(i), peer.initialNeighborsPort.get(i));
			client.setSoTimeout(10000);
			
			DataOutputStream out = new DataOutputStream(client.getOutputStream());
			out.writeBytes(Handshake.genHandshakeMessage(Integer.parseInt(peer.peerID)));
			
			BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
			if(Handshake.verifyHandshakeMessage(in.readLine(), Integer.parseInt(peer.initialNeighborsID.get(i)))) {
				PeerHandler peerHandler = new PeerHandler(peer);
				peerHandlers.add(peerHandler);
				threadPool.submit(peerHandler);
				peer.logger.logTCPConnection(peer.initialNeighborsID.get(i), Logger.Direction.CONNECT_TO);
			}
			
			out.close();
			in.close();
			client.close();
		}
	}
	
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
	
	void shutdown() throws InterruptedException, IOException {
		for (PeerHandler ph : peerHandlers)
			ph.shutdown();
		
		threadPool.shutdown();
		if(threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
			threadPool.shutdownNow();
			System.out.println("Executor service did not terminate in 30 secs.");
			System.out.println("May have runaway processes.");
		}
		
		server.close();
	}
}
