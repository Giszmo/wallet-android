/*
 * Copyright 2013, 2014 Megion Research & Development GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mrd.bitlib;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import com.mrd.bitlib.crypto.BitcoinSigner;
import com.mrd.bitlib.crypto.IPrivateKeyRing;
import com.mrd.bitlib.crypto.IPublicKeyRing;
import com.mrd.bitlib.model.*;
import com.mrd.bitlib.util.BitUtils;
import com.mrd.bitlib.util.Sha256Hash;

import java.util.*;
import java.util.stream.Collectors;

import static com.mrd.bitlib.TransactionUtils.MINIMUM_OUTPUT_VALUE;

public class StandardTransactionBuilder {
   // hash size 32 + output index size 4 + script length 1 + max. script size for compressed keys 107 + sequence number 4
   // also see https://github.com/bitcoin/bitcoin/blob/master/src/primitives/transaction.h#L190
   public static final int MAX_INPUT_SIZE = 32 + 4 + 1 + 107 + 4;
   // output value 8B + script length 1B + script 25B (always)
   private static final int OUTPUT_SIZE = 8 + 1 + 25;


   private static final int MAX_SEGWIT_INPUT_SIZE = 32 + 4 + 4;
   private static final int SEGWIT_OUTPUT_SIZE = 8 + 1 + 20;

   private NetworkParameters _network;
   private List<TransactionOutput> _outputs;

   public static class InsufficientFundsException extends Exception {
      //todo consider refactoring this into a composite return value instead of an exception. it is not really "exceptional"
      private static final long serialVersionUID = 1L;

      public long sending;
      public long fee;

      public InsufficientFundsException(long sending, long fee) {
         super("Insufficient funds to send " + sending + " satoshis with fee " + fee);
         this.sending = sending;
         this.fee = fee;
      }
   }

   public static class OutputTooSmallException extends Exception {
      //todo consider refactoring this into a composite return value instead of an exception. it is not really "exceptional"
      private static final long serialVersionUID = 1L;

      public long value;

      public OutputTooSmallException(long value) {
         super("An output was added with a value of " + value
             + " satoshis, which is smaller than the minimum accepted by the Bitcoin network");
      }
   }

   public static class UnableToBuildTransactionException extends Exception {
      private static final long serialVersionUID = 1L;

      public UnableToBuildTransactionException(String msg) {
         super(msg);
      }
   }

   public StandardTransactionBuilder(NetworkParameters network) {
      _network = network;
      _outputs = new LinkedList<>();
   }

   public void addOutput(Address sendTo, long value) throws OutputTooSmallException {
      addOutput(createOutput(sendTo, value, _network));
   }

   public void addOutput(TransactionOutput output) throws OutputTooSmallException {
      if (output.value < MINIMUM_OUTPUT_VALUE) {
         throw new OutputTooSmallException(output.value);
      }
      _outputs.add(output);
   }

   public void addOutputs(OutputList outputs) throws OutputTooSmallException {
      for (TransactionOutput output : outputs) {
         if (output.value > 0) {
            addOutput(output);
         }
      }
   }

   public static TransactionOutput createOutput(Address sendTo, long value, NetworkParameters network) {
      ScriptOutput script;
      if (sendTo.isP2SH(network)) {
         script = new ScriptOutputP2SH(sendTo.getTypeSpecificBytes());
      } else {
         script = new ScriptOutputStandard(sendTo.getTypeSpecificBytes());
      }
      return new TransactionOutput(value, script);
   }

   public static List<byte[]> generateSignatures(SigningRequest[] requests, IPrivateKeyRing keyRing) {
      List<byte[]> signatures = new LinkedList<>();
      for (SigningRequest request : requests) {
         BitcoinSigner signer = keyRing.findSignerByPublicKey(request.getPublicKey());
         if (signer == null) {
            // This should not happen as we only work on outputs that we have
            // keys for
            throw new RuntimeException("Private key not found");
         }
         byte[] signature = signer.makeStandardBitcoinSignature(request.getToSign());
         signatures.add(signature);
      }
      return signatures;
   }

   /**
    * Create an unsigned transaction and automatically calculate the miner fee.
    * <p>
    * If null is specified as the change address the 'richest' address that is part of the funding is selected as the
    * change address. This way the change always goes to the address contributing most, and the change will be less
    * than the contribution.
    *
    * @param inventory     The list of unspent transaction outputs that can be used as
    *                      funding
    * @param changeAddress The address to send any change to, can be null
    * @param keyRing       The public key ring matching the unspent outputs
    * @param network       The network we are working on
    * @param minerFeeToUse The miner fee in sat to pay for every kilobytes of transaction size
    * @return An unsigned transaction or null if not enough funds were available
    */
   public UnsignedTransaction createUnsignedTransaction(Collection<UnspentTransactionOutput> inventory,
                                                        Address changeAddress, IPublicKeyRing keyRing,
                                                        NetworkParameters network, long minerFeeToUse)
       throws InsufficientFundsException, UnableToBuildTransactionException {

      // Make a copy so we can mutate the list
      List<UnspentTransactionOutput> unspent = new LinkedList<>(inventory);
      CoinSelector coinSelector = new FifoCoinSelector(minerFeeToUse, unspent);
      long fee = coinSelector.getFee();
      long outputSum = coinSelector.getOutputSum();
      List<UnspentTransactionOutput> funding = pruneRedundantOutputs(coinSelector.getFundings(), fee + outputSum);
      boolean needChangeOutputInEstimation = needChangeOutputInEstimation(funding, outputSum, minerFeeToUse);

      // the number of inputs might have changed - recalculate the fee
      int outputsSizeInFeeEstimation = _outputs.size();
      if (needChangeOutputInEstimation) {
         outputsSizeInFeeEstimation += 1;
      }

      fee = estimateFee(funding.size(), outputsSizeInFeeEstimation, getSegwitOutputsCount(funding), minerFeeToUse);
      long found = 0;
      for (UnspentTransactionOutput output : funding) {
         found += output.value;
      }
      // We have found all the funds we need
      long toSend = fee + outputSum;

      if (changeAddress == null) {
         // If no change address is specified, get the richest address from the
         // funding set
         changeAddress = getRichest(funding, network);
      }

      // We have our funding, calculate change
      long change = found - toSend;

      // Get a copy of all outputs
      LinkedList<TransactionOutput> outputs = new LinkedList<>(_outputs);
      if(change >= MINIMUM_OUTPUT_VALUE) {
         TransactionOutput changeOutput = createOutput(changeAddress, change, _network);
         // Select a random position for our change so it is harder to analyze our addresses in the block chain.
         // It is OK to use the weak java Random class for this purpose.
         int position = new Random().nextInt(outputs.size() + 1);
         outputs.add(position, changeOutput);
      }

      UnsignedTransaction unsignedTransaction = new UnsignedTransaction(outputs, funding, keyRing, network, 0, UnsignedTransaction.NO_SEQUENCE);

      // check if we have a reasonable Fee or throw an error otherwise
      int estimateTransactionSize = estimateTransactionSize(unsignedTransaction.getFundingOutputs().length,
          unsignedTransaction.getOutputs().length, getSegwitOutputsCount(Arrays.asList(unsignedTransaction.getFundingOutputs())));
      long calculatedFee = unsignedTransaction.calculateFee();
      float estimatedFeePerKb = (long) ((float) calculatedFee / ((float) estimateTransactionSize / 1000)); // TODO change segwit

      // set a limit of MAX_MINER_FEE_PER_KB as absolute limit - it is very likely a bug in the fee estimator or transaction composer
      if (estimatedFeePerKb > Transaction.MAX_MINER_FEE_PER_KB) {
         throw new UnableToBuildTransactionException(
             String.format(Locale.getDefault(),
                 "Unreasonable high transaction fee of %s sat/1000Byte on a %d Bytes tx. Fee: %d sat, Suggested fee: %d sat",
                 estimatedFeePerKb, estimateTransactionSize, calculatedFee, minerFeeToUse)
         );
      }

      return unsignedTransaction;
   }

   /**
    * Get a number of segwit outputs from the entire list of output
    *
    * @param outputs A list of outputs
    * @return A number of segwit outputs
    */
   public static int getSegwitOutputsCount(Collection<UnspentTransactionOutput> outputs) {
      int segwitOutputs = 0;
      for(UnspentTransactionOutput u : outputs) {
         if (u.script instanceof ScriptOutputP2WPKH || u.script instanceof ScriptOutputP2SH) {
            segwitOutputs++;
         }
      }
      return segwitOutputs;
   }

   private boolean needChangeOutputInEstimation(List<UnspentTransactionOutput> funding,
                                                long outputSum, long minerFeeToUse) {

      long fee = estimateFee(funding.size(), _outputs.size(), getSegwitOutputsCount(funding), minerFeeToUse);

      long found = 0;
      for (UnspentTransactionOutput output : funding) {
         found += output.value;
      }
      // We have found all the funds we need
      long toSend = fee + outputSum;

      // We have our funding, calculate change
      long change = found - toSend;

      if (change >= MINIMUM_OUTPUT_VALUE) {
         // We need to add a change output in the estimation.
         return true;
      } else {
         // The change output would be smaller (or zero) than what the network would accept.
         // In this case we leave it be as a small increased miner fee.
         return false;
      }
   }


   /**
    * Greedy picks the biggest UTXOs until the outputSum is met.
    * @param funding UTXO list in any order
    * @param outputSum amount to spend
    * @return shuffled list of UTXOs
    */
   private List<UnspentTransactionOutput> pruneRedundantOutputs(List<UnspentTransactionOutput> funding, long outputSum) {
      List<UnspentTransactionOutput> largestToSmallest = Ordering.natural().reverse().onResultOf(new Function<UnspentTransactionOutput, Comparable>() {
         @Override
         public Comparable apply(UnspentTransactionOutput input) {
            return input.value;
         }
      }).sortedCopy(funding);

      long target = 0;
      for (int i = 0; i < largestToSmallest.size(); i++) {
         UnspentTransactionOutput output = largestToSmallest.get(i);
         target += output.value;
         if (target >= outputSum) {

            List<UnspentTransactionOutput> ret = largestToSmallest.subList(0, i + 1);
            Collections.shuffle(ret);
            return ret;
         }
      }
      return largestToSmallest;
   }

   @VisibleForTesting
   Address getRichest(Collection<UnspentTransactionOutput> unspent, final NetworkParameters network) {
      Preconditions.checkArgument(!unspent.isEmpty());
      Function<UnspentTransactionOutput, Address> txout2Address = new Function<UnspentTransactionOutput, Address>() {
         @Override
         public Address apply(UnspentTransactionOutput input) {
            return input.script.getAddress(network);
         }
      };
      Multimap<Address, UnspentTransactionOutput> index = Multimaps.index(unspent, txout2Address);
      Address ret = getRichest(index);
      return Preconditions.checkNotNull(ret);
   }

   private Address getRichest(Multimap<Address, UnspentTransactionOutput> index) {
      Address ret = null;
      long maxSum = 0;
      for (Address address : index.keys()) {
         Collection<UnspentTransactionOutput> unspentTransactionOutputs = index.get(address);
         long newSum = sum(unspentTransactionOutputs);
         if (newSum > maxSum) {
            ret = address;
            maxSum = newSum;
         }
      }
      return ret;
   }

   private long sum(Iterable<UnspentTransactionOutput> outputs) {
      long sum = 0;
      for (UnspentTransactionOutput output : outputs) {
         sum += output.value;
      }
      return sum;
   }

   public static Transaction finalizeTransaction(UnsignedTransaction unsigned, List<byte[]> signatures) {
      // Create finalized transaction inputs
      final UnspentTransactionOutput[] funding = unsigned.getFundingOutputs();
      TransactionInput[] inputs = new TransactionInput[funding.length];
      for (int i = 0; i < funding.length; i++) {
         if (isScriptInputSegWit(unsigned, i)) {
            inputs[i] = unsigned.getInputs()[i];
            InputWitness witness = new InputWitness(2);
            witness.setStack(0, signatures.get(i));
            witness.setStack(1, unsigned.getSigningRequests()[i].getPublicKey().getPublicKeyBytes());
            inputs[i].setWitness(witness);
         } else {
            // Create script from signature and public key
            ScriptInputStandard script = new ScriptInputStandard(signatures.get(i),
                    unsigned.getSigningRequests()[i].getPublicKey().getPublicKeyBytes());
            inputs[i] = new TransactionInput(funding[i].outPoint, script, unsigned.getDefaultSequenceNumber(), funding[i].value);
         }
      }

      // Create transaction with valid outputs and empty inputs
      return new Transaction(1, inputs, unsigned.getOutputs(), unsigned.getLockTime());
   }

   private static boolean isScriptInputSegWit(UnsignedTransaction unsigned, int i) {
      return unsigned.getInputs()[i].script instanceof ScriptInputP2WPKH || unsigned.getInputs()[i].script instanceof ScriptInputP2WSH;
   }

   private long outputSum() {
      long sum = 0;
      for (TransactionOutput output : _outputs) {
         sum += output.value;
      }
      return sum;
   }

   /**
    * Estimate the size of a transaction by taking the number of inputs and outputs into account. This allows us to
    * give a good estimate of the final transaction size, and determine whether out fee size is large enough.
    *
    * @param inputsTotal  the number of inputs of the transaction
    * @param outputsTotal the number of outputs of a transaction
    * @param segwitInputs  the number of segwit inputs of the transaction
    * @return The estimated transaction size in bytes
    */
   public static int estimateTransactionSize(int inputsTotal, int outputsTotal, int segwitInputs) {
      int totalOutputsSize = OUTPUT_SIZE * outputsTotal;

      int estimateExceptInputs = 0;
      estimateExceptInputs += 4; // Version info
      estimateExceptInputs += CompactInt.toBytes(inputsTotal).length; // num input encoding. Usually 1. >253 inputs -> 3
      estimateExceptInputs += CompactInt.toBytes(outputsTotal).length; // num output encoding. Usually 1. >253 outputs -> 3
      estimateExceptInputs += totalOutputsSize;
      estimateExceptInputs += 4; // nLockTime

      int estimateWithSignatures = estimateExceptInputs + MAX_INPUT_SIZE * inputsTotal;
      int estimateWithoutWitness = estimateExceptInputs + MAX_SEGWIT_INPUT_SIZE * segwitInputs + MAX_INPUT_SIZE * (inputsTotal - segwitInputs);

      return (estimateWithoutWitness * 3 + estimateWithSignatures) / 4;
   }

   /**
    * Returns the estimate needed fee in satoshis for a default P2PKH transaction with a certain number
    * of inputs and outputs and the specified per-kB-fee
    *
    * @param inputs  number of inputs
    * @param outputs number of outputs
    * @param minerFeePerKb miner fee in satoshis per kB
    **/
   public static long estimateFee(int inputs, int outputs, int segwitInputs, long minerFeePerKb) {
      // fee is based on the size of the transaction, we have to pay for
      // every 1000 bytes
      float txSizeKb = (float) (estimateTransactionSize(inputs, outputs, segwitInputs) / 1000.0); //in kilobytes
      return (long) (txSizeKb * minerFeePerKb);
   }

   private interface CoinSelector {
      List<UnspentTransactionOutput> getFundings();
      long getFee();
      long getOutputSum();
   }

   private class FifoCoinSelector implements CoinSelector {
      private List<UnspentTransactionOutput> allFunding;
      private long feeSat;
      private long outputSum;

      FifoCoinSelector(long feeSatPerKb, List<UnspentTransactionOutput> unspent)
          throws InsufficientFundsException {
         // Find the funding for this transaction
         allFunding = new LinkedList<>();
         feeSat = estimateFee(unspent.size(), 1, getSegwitOutputsCount(unspent), feeSatPerKb);
         outputSum = outputSum();
         long foundSat = 0;
         while (foundSat < feeSat + outputSum) {
            UnspentTransactionOutput unspentTransactionOutput = extractOldest(unspent);
            if (unspentTransactionOutput == null) {
               // We do not have enough funds
               throw new InsufficientFundsException(outputSum, feeSat);
            }
            foundSat += unspentTransactionOutput.value;
            allFunding.add(unspentTransactionOutput);

            feeSat = estimateFee(allFunding.size(), needChangeOutputInEstimation(allFunding, outputSum, feeSatPerKb)
                    ? _outputs.size() + 1
                    : _outputs.size(),
                    getSegwitOutputsCount(allFunding),
                    feeSatPerKb);
         }
      }

      @Override
      public List<UnspentTransactionOutput> getFundings() {
         return allFunding;
      }

      @Override
      public long getFee() {
         return feeSat;
      }

      @Override
      public long getOutputSum() {
         return outputSum;
      }

      private UnspentTransactionOutput extractOldest(Collection<UnspentTransactionOutput> unspent) {
         // find the "oldest" output
         int minHeight = Integer.MAX_VALUE;
         UnspentTransactionOutput oldest = null;
         for (UnspentTransactionOutput output : unspent) {
//         if (!(output.script instanceof ScriptOutputStandard)) {
//            // only look for standard scripts
//            continue;
//         } todo evauluate SegWit

            // Unconfirmed outputs have height = -1 -> change this to Int.MAX-1, so that we
            // choose them as the last possible option
            int height = output.height > 0 ? output.height : Integer.MAX_VALUE - 1;

            if (height < minHeight) {
               minHeight = height;
               oldest = output;
            }
         }
         if (oldest == null) {
            // There were no outputs
            return null;
         }
         unspent.remove(oldest);
         return oldest;
      }
   }
}
