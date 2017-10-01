package cnt5106c.p2p_file_sharing;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

class PeerHandler implements Runnable {
	peerProcess peer;
	boolean shutdown;
	
	PeerHandler(peerProcess peer) {
		this.peer = peer;
		shutdown = false;
		
		// send bitfield
	}
	
	public void run() {
		
		
		
	}
	


	void shutdown() throws InterruptedException, IOException {
		shutdown = true;
	}
}
