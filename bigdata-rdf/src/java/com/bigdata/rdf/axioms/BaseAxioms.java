/**

Copyright (C) SYSTAP, LLC 2006-2007.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
/*
 * Created on Mar 30, 2005
 */
package com.bigdata.rdf.axioms;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import org.openrdf.model.Value;

import com.bigdata.btree.BTree;
import com.bigdata.btree.ITuple;
import com.bigdata.btree.ITupleIterator;
import com.bigdata.btree.IndexMetadata;
import com.bigdata.btree.IndexMetadata.Options;
import com.bigdata.io.LongPacker;
import com.bigdata.rdf.internal.IV;
import com.bigdata.rdf.internal.IVUtility;
import com.bigdata.rdf.internal.TermId;
import com.bigdata.rdf.internal.VTE;
import com.bigdata.rdf.model.BigdataStatement;
import com.bigdata.rdf.model.BigdataURI;
import com.bigdata.rdf.model.BigdataValueFactory;
import com.bigdata.rdf.model.StatementEnum;
import com.bigdata.rdf.rio.StatementBuffer;
import com.bigdata.rdf.spo.ISPO;
import com.bigdata.rdf.spo.SPO;
import com.bigdata.rdf.spo.SPOKeyOrder;
import com.bigdata.rdf.spo.SPOTupleSerializer;
import com.bigdata.rdf.store.AbstractTripleStore;

import cutthecrap.utils.striterators.Resolver;
import cutthecrap.utils.striterators.Striterator;

/**
 * A collection of axioms.
 * <p>
 * Axioms are generated by {@link AbstractTripleStore#create()} based on
 * its configured properties. While the implementation class declares axioms in
 * terms of RDF {@link Value}s, the {@link BaseAxioms} only retains the set of
 * {s:p:o} tuples for the term identifiers corresponding those those
 * {@link Value}s. That {s:p:o} tuple array is the serialized state of this
 * class. When an {@link AbstractTripleStore} is reopened, the axioms are
 * de-serialized from a property in the global row store.
 * 
 * @author personickm
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public abstract class BaseAxioms implements Axioms, Externalizable {
    
    /**
     * The axioms in SPO order.
     */
    private transient BTree btree;
    
    /**
     * Used to format keys for that {@link BTree}.
     */
    private transient SPOTupleSerializer tupleSer;

    /**
     * Non-<code>null</code> iff the ctor specifies this value.
     */
    private final transient AbstractTripleStore db;

    /**
     * The value factory to be used when creating axioms.
     * 
     * @throws IllegalStateException
     *             unless the ctor variant was used that specifies the database.
     */
    final protected BigdataValueFactory getValueFactory() {
        
        return db.getValueFactory();
        
    }
    
    /**
     * De-serialization constructor.
     */
    protected BaseAxioms() {

        db = null;
        
    }
    
    /**
     * Ctor variant used by {@link AbstractTripleStore#create()}.
     * <p>
     * Note: When de-serializing a {@link BaseAxioms} object the zero-arg ctor
     * will be used and the {@link AbstractTripleStore} reference will not be
     * set.
     * 
     * @param db
     *            The database.
     */
    protected BaseAxioms(final AbstractTripleStore db) {
     
        this.db = db;
        
    }
    
    /**
     * Uses {@link #addAxioms(Collection)} to collect the declared axioms and
     * then writes the axioms onto the database specified to the
     * {@link BaseAxioms#BaseAxioms(AbstractTripleStore)} ctor.
     * 
     * @throws IllegalStateException
     *             if that ctor was not used.
     */
    final public void init() {

        if (db == null)
            throw new IllegalStateException();

        // setup [axioms] collection.
        final Set<BigdataStatement> axioms = new HashSet<BigdataStatement>(
                200);

        // obtain collection of axioms to be used.
        addAxioms(axioms);

        // write axioms onto the db.
        writeAxioms(axioms);
        
    }
    
    /**
     * Adds all axioms declared by this class into <i>axioms</i>.
     * <p>
     * Note: Subclasses MUST extend this method to add their axioms into the
     * <i>axioms</i> collection.
     * 
     * @param axioms
     *            A collection into which the axioms will be inserted.
     * 
     * @throws IllegalArgumentException
     *             if the parameter is <code>null</code>.
     */
    protected void addAxioms(final Collection<BigdataStatement> axioms) {

        if (axioms == null)
            throw new IllegalArgumentException();
        
        // NOP.
        
    }
    
    /**
     * Writes the axioms on the database, builds an internal B+Tree that is used
     * to quickly identify axioms based on {s:p:o} term identifier tuples, and
     * returns the distinct {s:p:o} term identifier tuples for the declared
     * axioms.
     * <p>
     * Note: if the terms for the axioms are already in the lexicon and the
     * axioms are already in the database then this will not write on the
     * database, but it will still result in the SPO[] containing the axioms to
     * be defined in {@link MyStatementBuffer}.
     */
    private void writeAxioms(final Collection<BigdataStatement> axioms) {
        
        if (axioms == null)
            throw new IllegalArgumentException();
        
        if (db == null)
            throw new IllegalStateException();
        
        final int naxioms = axioms.size();
        
        // SPO[] exposed by our StatementBuffer subclass.
        final SPO[] stmts;

        if (naxioms > 0) {

        // Note: min capacity of one handles case with no axioms.
            
            final int capacity = Math.max(1, naxioms);
            
            final MyStatementBuffer buffer = new MyStatementBuffer(db, capacity);

            for (Iterator<BigdataStatement> itr = axioms.iterator(); itr
                    .hasNext();) {

                final BigdataStatement triple = itr.next();

                assert triple.getStatementType() == StatementEnum.Axiom;

                buffer.add(triple);

            }

            // write on the database.
            buffer.flush();
        
            stmts = ((MyStatementBuffer)buffer).stmts;
            
        } else {
            
            stmts = null;
            
        }

		/*
		 * Fill the btree with the axioms in SPO order.
		 * 
		 * Note: This should ALWAYS use the SPO key order even for quads since
		 * we just want to test on the (s,p,o).
		 * 
		 * @todo This would be faster with a hashmap on the SPOs.
		 * 
		 * @todo There is no need to put the statement type into the in-memory
		 * axioms. they are axioms after all. That is, we could just have the
		 * keys and no values.
		 */
        {

            createBTree(naxioms/* naxioms */);
            
            if (stmts != null) {

                for (SPO spo : stmts) {

                    btree.insert(tupleSer.serializeKey(spo), spo
                            .getStatementType().serialize());

                }

            }

        }
        
    }
    
    /**
     * Create the {@link BTree} to hold the axioms.
     * 
     * @param naxioms
     *            The #of axioms (used to tune the branching factor).
     * 
     * @throws IllegalStateException
     *             if the {@link #btree} exists.
     */
    private void createBTree(final int naxioms) {
        
        if (btree != null)
            throw new IllegalStateException();
                
        // exact fill of the root leaf.
        final int branchingFactor = Math.max(Options.MIN_BRANCHING_FACTOR, naxioms );
        
        /*
         * Note: This uses a SimpleMemoryRawStore since we never explicitly
         * close the BaseAxioms class. Also, all data should be fully
         * buffered in the leaf of the btree so the btree will never touch
         * the store after it has been populated.
         */
        final IndexMetadata metadata = new IndexMetadata(UUID.randomUUID());
        
        metadata.setBranchingFactor(branchingFactor);

        tupleSer = new SPOTupleSerializer(SPOKeyOrder.SPO, false/* sids */);
        
        metadata.setTupleSerializer(tupleSer);
        
        btree = BTree.createTransient(metadata);
//        btree = BTree.create(new SimpleMemoryRawStore(), metadata);

    }

    /**
     * The initial version. The s, p, and o of each axiom were written out as
     * <code>long</code> integers.
     * 
     * TODO It is probably impossible to retain support for this version, but we
     * can always comment it out of the code easily enough.
     */
    private static final transient byte VERSION0 = 0;

    /**
     * The serialization format was changed when we introduced the TERMS index
     * (as opposed to the TERM2ID and ID2TERM index). Up to that point, the s,
     * p, and o components were always <code>long</code> termIds assigned by the
     * TERM2ID index. However, the refactor which introduced the TERMS index
     * generalized the {@link IV}s further such that we can no longer rely on
     * the <code>long</code> termId encoding. Therefore, the serialization of
     * the axioms was changed to the length of each {@link SPOKeyOrder#SPO}
     * index key followed by the <code>byte[]</code> comprising that key. This
     * has the effect of using the {@link IV} representation directly within the
     * serialization of the axioms.
     */ 
    private static final transient byte VERSION1 = 1;

    /**
     * The current version.
     */
    private static final transient byte currentVersion = VERSION1;

    public void readExternal(final ObjectInput in) throws IOException,
            ClassNotFoundException {

        final byte version = in.readByte();

        switch (version) {
        case VERSION0:
            readVersion0(in);
            break;
        case VERSION1:
            readVersion1(in);
            break;
        default:
            throw new UnsupportedOperationException("Unknown version: "
                    + version);
        }

    }

    @SuppressWarnings("unchecked")
    private void readVersion0(final ObjectInput in) throws IOException {

        final long naxioms = LongPacker.unpackLong(in);

        if (naxioms < 0 || naxioms > Integer.MAX_VALUE)
            throw new IOException();

        createBTree((int) naxioms);

        for (int i = 0; i < naxioms; i++) {

            final IV s = new TermId<BigdataURI>(VTE.URI, in.readLong());

            final IV p = new TermId<BigdataURI>(VTE.URI, in.readLong());

            final IV o = new TermId<BigdataURI>(VTE.URI, in.readLong());

            final SPO spo = new SPO(s, p, o, StatementEnum.Axiom);

            btree.insert(tupleSer.serializeKey(spo), spo.getStatementType()
                    .serialize());

        }
    }

    @SuppressWarnings("unchecked")
    private void readVersion1(final ObjectInput in) throws IOException {

        final long naxioms = LongPacker.unpackLong(in);

        if (naxioms < 0 || naxioms > Integer.MAX_VALUE)
            throw new IOException();

        createBTree((int) naxioms);

        for (int i = 0; i < naxioms; i++) {

            final long n = LongPacker.unpackLong(in);

            if (n < 0 || n > 1024)// reasonable upper bound.
                throw new IOException();

            final int nbytes = (int) n;

            final byte[] key = new byte[nbytes];

            in.readFully(key);

            final IV[] ivs = IVUtility.decodeAll(key);

            if (ivs.length != 3)
                throw new IOException();

            final IV s = ivs[0];

            final IV p = ivs[1];

            final IV o = ivs[2];

            final SPO spo = new SPO(s, p, o, StatementEnum.Axiom);

            btree.insert(tupleSer.serializeKey(spo), spo.getStatementType()
                    .serialize());

        }

    }

    public void writeExternal(final ObjectOutput out) throws IOException {

        if (btree == null)
            throw new IllegalStateException();

        out.writeByte(currentVersion);

        switch(currentVersion) {
        case VERSION0: writeVersion0(out); break;
        case VERSION1: writeVersion1(out); break;
        default: throw new AssertionError();
        }

    }

    private void writeVersion0(final ObjectOutput out) throws IOException {

        final long naxioms = btree.rangeCount();

        LongPacker.packLong(out, naxioms);

        @SuppressWarnings("unchecked")
        final ITupleIterator<SPO> itr = btree.rangeIterator();

        while (itr.hasNext()) {

            final SPO spo = itr.next().getObject();

            out.writeLong(spo.s().getTermId());

            out.writeLong(spo.p().getTermId());

            out.writeLong(spo.o().getTermId());

        }

    }

    private void writeVersion1(final ObjectOutput out) throws IOException {

        final long naxioms = btree.rangeCount();

        LongPacker.packLong(out, naxioms);

        @SuppressWarnings("unchecked")
        final ITupleIterator<SPO> itr = btree.rangeIterator();

        while (itr.hasNext()) {

            final ITuple<SPO> t = itr.next();

            final byte[] key = t.getKey();

            LongPacker.packLong(out, key.length);

            out.write(key);

        }

    }
    
    final public boolean isAxiom(final IV s, final IV p, final IV o) {

        if (btree == null)
            throw new IllegalStateException();

        // fast rejection.
        if (s == null || p == null || o == null) {

            return false;
            
        }

        final byte[] key = tupleSer.serializeKey(new SPO(s, p, o));
        
        if(btree.contains(key)) {
            
            return true;
            
        }
        
        return false;
        
    }

    public final int size() {
        
        if (btree == null)
            throw new IllegalStateException();
        
        return (int) btree.rangeCount();
        
    }
    
    @SuppressWarnings("unchecked")
    final public Iterator<SPO> axioms() {
        
        if (btree == null)
            throw new IllegalStateException();
        
        final ITupleIterator<SPO> itr = btree.rangeIterator();
        
        return new Striterator(itr).addFilter(new Resolver() {

            private static final long serialVersionUID = 1L;

            @Override
            protected Object resolve(Object obj) {
                return ((ITuple<ISPO>)obj).getObject();
            }
            
        });
        
    }

    /**
     * Helper class.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    private static class MyStatementBuffer extends StatementBuffer {

        /**
         * An array of the axioms in SPO order.
         */
        SPO[] stmts;

        /**
         * @param database
         * @param capacity
         */
        public MyStatementBuffer(final AbstractTripleStore database,
                final int capacity) {

            super(database, capacity);

        }

        /**
         * Overridden to save off a copy of the axioms in SPO order on
         * {@link #stmts} where we can access them afterwards.
         */
        @Override
        protected long writeSPOs(final SPO[] stmts, final int numStmts) {
            
            if (this.stmts == null) {

                this.stmts = new SPO[numStmts];

                System.arraycopy(stmts, 0, this.stmts, 0, numStmts);

                Arrays.sort( this.stmts, SPOKeyOrder.SPO.getComparator() );
                
            }
            
            return super.writeSPOs(stmts, numStmts);
            
        }
        
    }
    
}

