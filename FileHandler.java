package cnt5106c.p2p_file_sharing;

import java.io.RandomAccessFile;
import java.io.FileNotFoundException;
import java.io.IOException;

class FileHandler {
	private peerProcess peer;
	private RandomAccessFile file;
	
	FileHandler(peerProcess peer) throws FileNotFoundException, IOException {
		this.peer = peer;
		
		if(peer.peerInfos.get(peer.peerID).hasFile) {
			file = new RandomAccessFile("peer_" + peer.peerID + "/" + peer.fileName, "r");
			try {
				if(file.length() != peer.fileSize)
					System.out.println("Does not have complete " + peer.fileName + ".");
			}
			catch(FileNotFoundException e) {
				System.out.println("Cannot find " + peer.fileName + ".");
				throw e;
			}
			finally {
				file.close();
			}
		}
		
		try {
			file = new RandomAccessFile("peer_" + peer.peerID + "/" + peer.fileName, "rw");
			file.setLength(peer.fileSize);
		}
		catch(FileNotFoundException e) {
			System.out.println("Failed to open " + peer.fileName + ".");
			throw e;
		}
	}
	
	byte[] readPiece(int pieceIndex) throws IOException {
		byte piece[] = new byte[peer.pieceSize];
		
		try {
			file.read(piece, pieceIndex * peer.pieceSize, peer.pieceSize);
		}
		catch(IOException e) {
			System.out.println("Failed to read from " + peer.fileName + ".");
			throw e;
		}
		
		return piece;
	}
	
	void writePiece(byte[] piece, int pieceIndex) throws IOException {
		try {
			file.write(piece, pieceIndex * peer.pieceSize, peer.pieceSize);
		}
		catch(IOException e) {
			System.out.println("Failed to write to " + peer.fileName + ".");
			throw e;
		}
	}
}
