package cnt5106c.p2p_file_sharing;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.BitSet;
import java.util.Scanner;

public class peerProcess {
	private static final String commonConfigPath = "Common.cfg";
	private static final String peerInfoConfigPath = "PeerInfo.cfg";
	
	final int numPrefNeighbor;
	final int unchokeInterval;
	final int optUnchokeInterval;
	final String fileName;
	final int fileSize;
	final int pieceSize;
	final String peerID;
	final int port;
	final int pieceCount;
	BitSet bitfield;
	Logger logger;

	public static void main(String[] args) throws FileNotFoundException, IOException {
		peerProcess peer = new peerProcess(args[0]);
		
		try {
			peer = new peerProcess(args[0]);
		}
		catch(FileNotFoundException e) {
			System.out.println("Cannot find config files.");
			return;
		}
		catch(IOException e) {
			System.out.println("Failed to read config files.");
			return;
		}
		
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
		BufferedReader peerInfoConfigReader = new BufferedReader(new FileReader(peerInfoConfigPath));
		
		try {
			peerID = new String(arg);
			
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
			
			line = peerInfoConfigReader.readLine();
			while(line != null) {
				String info[] = line.split(" ");
				if(info[0].equals(peerID)) {
					port = Integer.parseInt(info[2]);
					
					int temp = fileSize / pieceSize;
					if(fileSize % pieceSize != 0)
						temp++;
					pieceCount = temp;
					bitfield = new BitSet(pieceCount);
					if(Integer.parseInt(info[3]) == 1) {
						for(int i = 0; i < bitfield.size(); i++)
							bitfield.set(i);
					}
					
					logger = new Logger(info[0]);
					
					peerInfoConfigReader.close();
					return;
				}
			}
			throw new IOException();
		}
		finally {
			commonConfigReader.close();
			peerInfoConfigReader.close();
		}
	}
}
