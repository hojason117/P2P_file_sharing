package cnt5106c.p2p_file_sharing;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.BitSet;
import java.util.Scanner;
import java.util.ArrayList;
import java.util.HashMap;

public class peerProcess {
	private static final String commonConfigPath = "Common.cfg";
	private static final String peerInfoConfigPath = "PeerInfo.cfg";
	
	final int numPrefNeighbor;
	final int unchokeInterval;
	final int optUnchokeInterval;
	final String fileName;
	final int fileSize;
	final int pieceSize;
	final int pieceCount;
	final String peerID;
	HashMap<String, PeerInfo> peerInfos;
	ArrayList<String> peerIDs;
	ArrayList<String> previousPeersID;
	Controller controller;
	FileHandler fileHandler;
	Logger logger;

	public static void main(String[] args) throws InterruptedException, IOException {
		peerProcess peer;
		
		try {
			peer = new peerProcess(args[0]);
		}
		catch(Exception e) {
			return;
		}
		
		peer.start();
		
		Thread console = new Thread(new ConsoleControl(peer));
		console.setDaemon(true);
		console.start();
	}
	
	// peerProcess constructor : initialize config parameters
	peerProcess(String arg) throws FileNotFoundException, IOException {
		BufferedReader commonConfigReader = new BufferedReader(new FileReader(commonConfigPath));
		try {
			String line;
			line = commonConfigReader.readLine();
			numPrefNeighbor = Integer.parseInt(line.substring(27));
			line = commonConfigReader.readLine();
			unchokeInterval = Integer.parseInt(line.substring(18));
			line = commonConfigReader.readLine();
			optUnchokeInterval = Integer.parseInt(line.substring(28));
			line = commonConfigReader.readLine();
			fileName = line.substring(9);
			line = commonConfigReader.readLine();
			fileSize = Integer.parseInt(line.substring(9));
			line = commonConfigReader.readLine();
			pieceSize = Integer.parseInt(line.substring(10));
			
			int temp = fileSize / pieceSize;
			if(fileSize % pieceSize != 0)
				temp++;
			pieceCount = temp;
		}
		catch(FileNotFoundException e) {
			System.out.println("Cannot find " + commonConfigPath + ".");
			throw e;
		}
		catch(IOException e) {
			System.out.println("Failed to read " + commonConfigPath + ".");
			throw e;
		}
		finally {
			commonConfigReader.close();
		}
		
		BufferedReader peerInfoConfigReader = new BufferedReader(new FileReader(peerInfoConfigPath));
		try {
			peerID = new String(arg);
			previousPeersID = new ArrayList<String>();
			
			// Collect info for all peers
			boolean readSelf = false;
			String line;
			line = peerInfoConfigReader.readLine();
			while(line != null) {
				String info[] = line.split(" ");
				
				if(info[0].equals(peerID))
					readSelf = true;
				
				PeerInfo newPeer;
				if(Integer.parseInt(info[3]) == 1)
					newPeer = new PeerInfo(info[0], info[1], Integer.parseInt(info[2]), true);
				else
					newPeer = new PeerInfo(info[0], info[1], Integer.parseInt(info[2]), false);
				
				peerInfos.put(info[0], newPeer);
				
				peerIDs.add(info[0]);
				
				if(!readSelf)
					previousPeersID.add(info[0]);
				
				line = peerInfoConfigReader.readLine();
			}
			
			// Set up own info
			PeerInfo peer = peerInfos.get(peerID);
			if(peer == null) {
				System.out.println("Cannot find peer info in " + peerInfoConfigPath + ".");
				throw new IOException();
			}
			
			peer.bitfield = new BitSet(pieceCount);
			if(peer.hasFile) {
				for(int i = 0; i < peer.bitfield.size(); i++)
					peer.bitfield.set(i);
			}
			
			controller = new Controller(this);
			fileHandler = new FileHandler(this);
			logger = new Logger(peerID);
		}
		catch(FileNotFoundException e) {
			System.out.println("Cannot find " + peerInfoConfigPath + ".");
			throw e;
		}
		catch(IOException e) {
			System.out.println("Failed to read " + peerInfoConfigPath + ".");
			throw e;
		}
		finally {
			peerInfoConfigReader.close();
		}
	}
	
	// Holds information for a peer
	class PeerInfo {
		final String ID;
		final String addr;
		final int port;
		boolean hasFile;
		BitSet bitfield;
		boolean choked;
		PeerHandler handler;
		boolean interested;
		
		PeerInfo(String id, String addr, int port, boolean hasFile) {
			ID = id;
			this.addr = addr;
			this.port = port;
			this.hasFile = hasFile;
			bitfield = null;
			choked = true;
			handler = null;
			interested = false;
		}
	}
	
	// Daemon thread for console control
	private static class ConsoleControl implements Runnable {
		final peerProcess peer;
		
		ConsoleControl(peerProcess peer) {
			this.peer = peer;
		}
		
		public void run() {
			Scanner scanner = new Scanner(System.in);
			String op;
			while(true) {
				System.out.print("Option('h' for help): ");
				op = scanner.nextLine();
				switch(op) {
				case "h":
					System.out.println("'h' for help");
					System.out.println("'q' to terminate process");
					System.out.println("'d' to toggle console display");
					break;
				case "q":
					scanner.close();
					try {
						peer.controller.shutdown();
					}
					catch(IOException | InterruptedException e) {
						System.out.println("Failed to shutdown controller.");
						return;
					}
					return;
				case "d":
					peer.logger.toggleConsoleDisplay();
					break;
					
				default:
					break;
				}
			}
		}
	}
	
	// Start peerProcess : open a thread for main loop
	void start() {
		Thread control = new Thread(controller);
		control.start();
	}
}
