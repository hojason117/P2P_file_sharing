package cnt5106c.p2p_file_sharing;

import java.io.PrintWriter;
import java.io.FileNotFoundException;
import java.time.LocalDateTime;
import java.util.List;

public class Logger {
	private final String peerID;
	private final String logFileName;
	private PrintWriter writer;
	private boolean consoleDisplay;
	
	static enum Direction {
		CONNECT_TO,	
		CONNECT_FROM;
	}
	
	Logger(String peerID) throws FileNotFoundException {
		this.peerID = peerID;
		logFileName = "log_peer_" + peerID + ".log";
		writer = new PrintWriter(logFileName);
		consoleDisplay = false;
	}
	
	void logTCPConnection(String partnerID, Direction dir) {
		String currentTime = getCurrentTime();
		
		String log;
		if(dir == Direction.CONNECT_TO)
			log = currentTime + ": Peer " + peerID + " makes a connection to Peer " + partnerID + ".";
		else
			log = currentTime + ": Peer " + peerID + " is connected from Peer " + partnerID + ".";
		
		synchronized(writer) {
			writer.println(log);
			writer.flush();
		}
		if(consoleDisplay)
			System.out.println(log);
	}
	
	void logChangePreferredNeighbor(List<String> neighbors) {
		String currentTime = getCurrentTime();
		
		String log = currentTime + ": Peer " + peerID + " has the preferred neighbors ";
		if(!neighbors.isEmpty()) {
			for(int i = 0; i < neighbors.size() - 1; i++)
				log += neighbors.get(i) + ",";
			log += neighbors.get(neighbors.size() - 1);
		}
		log += ".";
		
		synchronized(writer) {
			writer.println(log);
			writer.flush();
		}
		if(consoleDisplay)
			System.out.println(log);
	}
	
	void logChangeOptimisticallyUnchokedNeighbor(String neighborID) {
		String currentTime = getCurrentTime();
		String log = currentTime + ": Peer " + peerID + " has the optimistically unchoked neighbor " + neighborID + ".";
		
		synchronized(writer) {
			writer.println(log);
			writer.flush();
		}
		if(consoleDisplay)
			System.out.println(log);
	}
	
	void logUnchoking(String neighborID) {
		String currentTime = getCurrentTime();
		String log = currentTime + ": Peer " + peerID + " is unchoked by " + neighborID + ".";
		
		synchronized(writer) {
			writer.println(log);
			writer.flush();
		}
		if(consoleDisplay)
			System.out.println(log);
	}
	
	void logChoking(String neighborID) {
		String currentTime = getCurrentTime();
		String log = currentTime + ": Peer " + peerID + " is choked by " + neighborID + ".";
		
		synchronized(writer) {
			writer.println(log);
			writer.flush();
		}
		if(consoleDisplay)
			System.out.println(log);
	}
	
	void logReceiveHave(String partner, int pieceIndex) {
		String currentTime = getCurrentTime();
		String log = currentTime + ": Peer " + peerID + " received the 'have' message from " + partner + " for the piece " + pieceIndex + ".";
		
		synchronized(writer) {
			writer.println(log);
			writer.flush();
		}
		if(consoleDisplay)
			System.out.println(log);
	}	
	
	void logReceiveInterested(String partner) {
		String currentTime = getCurrentTime();
		String log = currentTime + ": Peer " + peerID + " received the 'interested' message from " + partner + ".";
		
		synchronized(writer) {
			writer.println(log);
			writer.flush();
		}
		if(consoleDisplay)
			System.out.println(log);
	}
	
	void logReceiveNotInterested(String partner) {
		String currentTime = getCurrentTime();
		String log = currentTime + ": Peer " + peerID + " received the 'not interested' message from " + partner + ".";
		
		synchronized(writer) {
			writer.println(log);
			writer.flush();
		}
		if(consoleDisplay)
			System.out.println(log);
	}
	
	void logDownloadPiece(String partner, int pieceIndex, int totalPieces) {
		String currentTime = getCurrentTime();
		String log = currentTime + ": Peer " + peerID + " has downloaded the piece " + String.format("%5d", pieceIndex) + " from " + partner + ". ";
		log += "Now the number of pieces it has is " + String.format("%5d", totalPieces) + ".";
		
		synchronized(writer) {
			writer.println(log);
			writer.flush();
		}
		if(consoleDisplay)
			System.out.println(log);
	}
	
	void logDownloadComplete() {
		String currentTime = getCurrentTime();
		String log = currentTime + ": Peer " + peerID + " has downloaded the complete file.";
		
		synchronized(writer) {
			writer.println(log);
			writer.flush();
		}
		if(consoleDisplay)
			System.out.println(log);
	}
	
	private String getCurrentTime() {
		LocalDateTime time = LocalDateTime.now();
		String currentTime = time.getYear() + "-" + String.format("%2s", time.getMonthValue()) + "-" + String.format("%2s", time.getDayOfMonth()) + " " 
				+ String.format("%2s", time.getHour()) + ":" + String.format("%2s", time.getMinute()) + ":" + String.format("%2s", time.getSecond());
		
		return currentTime;
	}
	
	void toggleConsoleDisplay() {
		consoleDisplay = (consoleDisplay) ? false : true;
	}
	
	void closeFile() {
		writer.close();
	}
}
