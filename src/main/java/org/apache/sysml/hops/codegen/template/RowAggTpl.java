/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.hops.codegen.template;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;

import org.apache.sysml.hops.AggBinaryOp;
import org.apache.sysml.hops.AggUnaryOp;
import org.apache.sysml.hops.BinaryOp;
import org.apache.sysml.hops.Hop;
import org.apache.sysml.hops.codegen.cplan.CNode;
import org.apache.sysml.hops.codegen.cplan.CNodeBinary;
import org.apache.sysml.hops.codegen.cplan.CNodeBinary.BinType;
import org.apache.sysml.hops.codegen.cplan.CNodeData;
import org.apache.sysml.hops.codegen.cplan.CNodeRowAgg;
import org.apache.sysml.hops.codegen.cplan.CNodeTpl;
import org.apache.sysml.hops.codegen.cplan.CNodeUnary;
import org.apache.sysml.hops.codegen.cplan.CNodeUnary.UnaryType;
import org.apache.sysml.hops.codegen.template.CPlanMemoTable.MemoTableEntry;
import org.apache.sysml.hops.rewrite.HopRewriteUtils;
import org.apache.sysml.hops.Hop.AggOp;
import org.apache.sysml.hops.Hop.Direction;
import org.apache.sysml.hops.Hop.OpOp2;
import org.apache.sysml.parser.Expression.DataType;
import org.apache.sysml.runtime.matrix.data.Pair;

public class RowAggTpl extends BaseTpl {

	public RowAggTpl() {
		super(TemplateType.RowAggTpl);
	}
	
	@Override
	public boolean open(Hop hop) {
		//any unary or binary aggregate operation with a vector output, but exclude binary aggregate
		//with transposed input to avoid counter-productive fusion
		return ( ((hop instanceof AggBinaryOp && hop.getInput().get(1).getDim1()>1
				&& !HopRewriteUtils.isTransposeOperation(hop.getInput().get(0))) 
			|| (hop instanceof AggUnaryOp && ((AggUnaryOp)hop).getOp()==AggOp.SUM ))  			  
				&& ( (hop.getDim1()==1 && hop.getDim2()!=1) || (hop.getDim1()!=1 && hop.getDim2()==1) )  );
	}

	@Override
	public boolean fuse(Hop hop, Hop input) {
		return !isClosed() && 
			(  (hop instanceof BinaryOp && (HopRewriteUtils.isBinaryMatrixColVectorOperation(hop)
					|| HopRewriteUtils.isBinaryMatrixScalarOperation(hop))) 
			|| (hop instanceof AggUnaryOp && ((AggUnaryOp)hop).getDirection()==Direction.Col)
			|| (hop instanceof AggBinaryOp && HopRewriteUtils.isTransposeOperation(hop.getInput().get(0))));
	}

	@Override
	public boolean merge(Hop hop, Hop input) {
		//merge rowagg tpl with cell tpl if input is a vector
		return !isClosed() &&
			(hop instanceof BinaryOp && input.getDim2()==1 ); //matrix-scalar/vector-vector ops )
	}

	@Override
	public CloseType close(Hop hop) {
		//close on column aggregate (e.g., colSums, t(X)%*%y)
		if( hop instanceof AggUnaryOp && ((AggUnaryOp)hop).getDirection()==Direction.Col
			|| (hop instanceof AggBinaryOp && HopRewriteUtils.isTransposeOperation(hop.getInput().get(0))) )
			return CloseType.CLOSED_VALID;
		else
			return CloseType.OPEN;
	}

	@Override
	public Pair<Hop[], CNodeTpl> constructCplan(Hop hop, CPlanMemoTable memo, boolean compileLiterals) {
		//recursively process required cplan output
		HashSet<Hop> inHops = new HashSet<Hop>();
		HashMap<Long, CNode> tmp = new HashMap<Long, CNode>();
		hop.resetVisitStatus();
		rConstructCplan(hop, memo, tmp, inHops, compileLiterals);
		hop.resetVisitStatus();
		
		//reorder inputs (ensure matrix is first input)
		LinkedList<Hop> sinHops = new LinkedList<Hop>(inHops);
		for( Hop h : inHops )
			if( h.getDataType().isMatrix() && !TemplateUtils.isVector(h) ) {
				sinHops.remove(h);
				sinHops.addFirst(h);
			}
		
		//construct template node
		ArrayList<CNode> inputs = new ArrayList<CNode>();
		for( Hop in : sinHops )
			inputs.add(tmp.get(in.getHopID()));
		CNode output = tmp.get(hop.getHopID());
		CNodeRowAgg tpl = new CNodeRowAgg(inputs, output);
		
		// return cplan instance
		return new Pair<Hop[],CNodeTpl>(sinHops.toArray(new Hop[0]), tpl);
	}

	private void rConstructCplan(Hop hop, CPlanMemoTable memo, HashMap<Long, CNode> tmp, HashSet<Hop> inHops, boolean compileLiterals) 
	{	
		//recursively process required childs
		MemoTableEntry me = memo.getBest(hop.getHopID(), TemplateType.RowAggTpl);
		for( int i=0; i<hop.getInput().size(); i++ ) {
			Hop c = hop.getInput().get(i);
			if( me.isPlanRef(i) )
				rConstructCplan(c, memo, tmp, inHops, compileLiterals);
			else {
				CNodeData cdata = TemplateUtils.createCNodeData(c, compileLiterals);	
				tmp.put(c.getHopID(), cdata);
				inHops.add(c);
			}
		}
				
		//construct cnode for current hop
		CNode out = null;
		if(hop instanceof AggUnaryOp)
		{
			CNode cdata1 = tmp.get(hop.getInput().get(0).getHopID());
			if(  ((AggUnaryOp)hop).getDirection() == Direction.Row && ((AggUnaryOp)hop).getOp() == AggOp.SUM  ) {
				if(hop.getInput().get(0).getDim2()==1)
					out = (cdata1.getDataType()==DataType.SCALAR) ? cdata1 : new CNodeUnary(cdata1,UnaryType.LOOKUP_R);
				else
					out = new CNodeUnary(cdata1, UnaryType.ROW_SUMS);
			}
			else  if (((AggUnaryOp)hop).getDirection() == Direction.Col && ((AggUnaryOp)hop).getOp() == AggOp.SUM ) {
				//vector div add without temporary copy
				if(cdata1 instanceof CNodeBinary && ((CNodeBinary)cdata1).getType()==BinType.VECT_DIV_SCALAR)
					out = new CNodeBinary(cdata1.getInput().get(0), cdata1.getInput().get(1), BinType.VECT_DIV_ADD);
				else	
					out = cdata1;
			}
		}
		else if(hop instanceof AggBinaryOp)
		{
			CNode cdata1 = tmp.get(hop.getInput().get(0).getHopID());
			CNode cdata2 = tmp.get(hop.getInput().get(1).getHopID());
			
			if( HopRewriteUtils.isTransposeOperation(hop.getInput().get(0)) )
			{
				//correct input under transpose
				cdata1 = TemplateUtils.skipTranspose(cdata1, hop.getInput().get(0), tmp, compileLiterals);
				inHops.remove(hop.getInput().get(0)); 
				inHops.add(hop.getInput().get(0).getInput().get(0));
				
				out = new CNodeBinary(cdata2, cdata1, BinType.VECT_MULT_ADD);
			}
			else
			{
				if(hop.getInput().get(0).getDim2()==1 && hop.getInput().get(1).getDim2()==1)
					out = new CNodeBinary((cdata1.getDataType()==DataType.SCALAR)? cdata1 : new CNodeUnary(cdata1, UnaryType.LOOKUP0),
						(cdata2.getDataType()==DataType.SCALAR)? cdata2 : new CNodeUnary(cdata2, UnaryType.LOOKUP0), BinType.MULT);
				else	
					out = new CNodeBinary(cdata1, cdata2, BinType.DOT_PRODUCT);
			}
		}
		else if(hop instanceof BinaryOp)
		{
			CNode cdata1 = tmp.get(hop.getInput().get(0).getHopID());
			CNode cdata2 = tmp.get(hop.getInput().get(1).getHopID());
			
			// if one input is a matrix then we need to do vector by scalar operations
			if(hop.getInput().get(0).getDim1() > 1 && hop.getInput().get(0).getDim2() > 1 )
			{
				if (((BinaryOp)hop).getOp()== OpOp2.DIV)
					out = new CNodeBinary(cdata1, cdata2, BinType.VECT_DIV_SCALAR);
			}
			else //one input is a vector/scalar other is a scalar
			{
				String primitiveOpName = ((BinaryOp)hop).getOp().toString();
				if( (cdata1.getNumRows() > 1 && cdata1.getNumCols() == 1) || (cdata1.getNumRows() == 1 && cdata1.getNumCols() > 1) ) {
					cdata1 = new CNodeUnary(cdata1, UnaryType.LOOKUP_R);
				}
				if( (cdata2.getNumRows() > 1 && cdata2.getNumCols() == 1) || (cdata2.getNumRows() == 1 && cdata2.getNumCols() > 1) ) {
					cdata2 = new CNodeUnary(cdata2, UnaryType.LOOKUP_R);
				}
				out = new CNodeBinary(cdata1, cdata2, BinType.valueOf(primitiveOpName));	
			}
		}
		
		if( out.getDataType().isMatrix() ) {
			out.setNumRows(hop.getDim1());
			out.setNumCols(hop.getDim2());
		}
		
		tmp.put(hop.getHopID(), out);
	}
}
