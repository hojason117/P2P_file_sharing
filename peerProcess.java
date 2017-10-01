package cnt5106c.p2p_file_sharing;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.BitSet;
import java.util.Scanner;
import java.util.ArrayList;

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
	final String addr;
	final int port;
	boolean hasFile;
	BitSet bitfield;
	ArrayList<String> initialNeighborsID;
	ArrayList<String> initialNeighborsAddr;
	ArrayList<Integer> initialNeighborsPort;
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
		
		// Console control
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
				peer.controller.shutdown();
				return;
			case "d":
				peer.logger.toggleConsoleDisplay();
				break;
				
			default:
				break;
			}
		}
	}
	
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
			
			String line;
			line = peerInfoConfigReader.readLine();
			while(line != null) {
				String info[] = line.split(" ");
				if(info[0].equals(peerID)) {
					addr = info[1];
					port = Integer.parseInt(info[2]);
					
					bitfield = new BitSet(pieceCount);
					hasFile = false;
					if(Integer.parseInt(info[3]) == 1) {
						for(int i = 0; i < bitfield.size(); i++)
							bitfield.set(i);
						hasFile = true;
					}
					
					controller = new Controller(this);
					fileHandler = new FileHandler(this);
					logger = new Logger(peerID);
					
					peerInfoConfigReader.close();
					return;
				}
				else {
					initialNeighborsID.add(info[0]);
					initialNeighborsAddr.add(info[1]);
					initialNeighborsPort.add(Integer.parseInt(info[2]));
				}
			}
			throw new IOException();
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
	
	void start() {
		Thread control = new Thread(controller);
		control.start();
	}
}
