import org.bitcoinj.core.*;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.utils.BriefLogFormatter;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.Future;

/**
 * <p>Downloads the block given a block hash from the remote or localhost node and prints it out.</p>
 * <p>When downloading from localhost, run bitcoind locally: bitcoind -testnet -daemon.
 * After bitcoind is up and running, use command: org.bitcoinj.examples.FetchBlock --localhost &lt;blockHash&gt; </p>
 * <p>Otherwise, use command: org.bitcoinj.examples.FetchBlock &lt;blockHash&gt;, this command will download blocks from a peer generated by DNS seeds.</p>
 */
public class FetchBlock {

    public static void main(String[] args) throws Exception {
        Sha256Hash blockHash = Sha256Hash.wrap("0000000000000000002214f7766f846fedd7a8f5bb7c3d65d15f13158fe907f0");
        int numOfBlocksToDownload = 10;
        int blockHeight = 564948;

        BriefLogFormatter.init();

        // Connect to testnet and find a peer
        System.out.println("Connecting to node");
        final NetworkParameters params = MainNetParams.get();

        BlockStore blockStore = new MemoryBlockStore(params);
        BlockChain chain = new BlockChain(params, blockStore);
        PeerGroup peerGroup = new PeerGroup(params, chain);

        peerGroup.addPeerDiscovery(new DnsDiscovery(params));
        peerGroup.start();

        peerGroup.waitForPeers(2).get();
        Peer peer = peerGroup.getConnectedPeers().get(0);

        BitcoinSerializer bitcoinSerializer = new BitcoinSerializer(params, false);

        for (int i=numOfBlocksToDownload; i>0; i--) {
            Future<Block> future = peer.getBlock(blockHash);

            System.out.println("Waiting for node to send us the requested block: " + blockHash.toString());

            Block block = future.get();


            FileOutputStream outputStream = new FileOutputStream(new File("bitcoinblocks/blocks_" + blockHeight + ".dat"));
            bitcoinSerializer.serialize(block, outputStream);

            blockHeight--;

            outputStream.close();

            System.out.println("Downloaded and saved block: " + block.getHashAsString());

            // update the hash
            blockHash = block.getPrevBlockHash();
        }

        peerGroup.stopAsync();
    }
}