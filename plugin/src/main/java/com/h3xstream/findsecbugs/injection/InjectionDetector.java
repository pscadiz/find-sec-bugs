/**
 * Find Security Bugs
 * Copyright (c) Philippe Arteau, All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */
package com.h3xstream.findsecbugs.injection;

import com.h3xstream.findsecbugs.common.ByteCode;
import com.h3xstream.findsecbugs.injection.util.ByteCodeReplay;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.Detector;
import edu.umd.cs.findbugs.Priorities;
import edu.umd.cs.findbugs.ba.*;
import edu.umd.cs.findbugs.ba.constant.Constant;
import edu.umd.cs.findbugs.ba.constant.ConstantDataflow;
import edu.umd.cs.findbugs.ba.constant.ConstantFrame;

import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Class inspired by the detector FindSqlInjection
 */
public abstract class InjectionDetector implements Detector {

    private static final boolean DEBUG = false;

    private BugReporter bugReporter;

    protected InjectionDetector(BugReporter bugReporter) {
        this.bugReporter = bugReporter;
    }

    @Override
    public void visitClassContext(ClassContext classContext) {
        JavaClass javaClass = classContext.getJavaClass();
        Method[] methodList = javaClass.getMethods();

        ConstantPoolGen cpg = classContext.getConstantPoolGen();

        List<InjectionSource> selectedSources = new ArrayList<InjectionSource>();

        for (InjectionSource source : getInjectionSource()) {
            if (source.isCandidate(cpg)) {
                selectedSources.add(source);
            }
        }

        if (selectedSources.size() > 0) {
            for (Method method : methodList) {
                MethodGen methodGen = classContext.getMethodGen(method);
                if (methodGen == null)
                    continue;

                try {
                    analyzeMethod(classContext, method, selectedSources);
                } catch (DataflowAnalysisException e) {
                } catch (CFGBuilderException e) {
                }
            }
        }
    }

    private void analyzeMethod(ClassContext classContext, Method method, List<InjectionSource> selectedSources) throws DataflowAnalysisException, CFGBuilderException {
        if(DEBUG) System.out.println(">>> "+method.getName()+"() <<<");
        JavaClass javaClass = classContext.getJavaClass();

        MethodGen methodGen = classContext.getMethodGen(method);
        if (methodGen == null)
            return;

        ConstantPoolGen cpg = methodGen.getConstantPool();
        CFG cfg = classContext.getCFG(method);




        ConstantDataflow dataflow = classContext.getConstantDataflow(method);
        for (Location location: cfg.orderedLocations() ) {

            InstructionHandle insHandle = location.getHandle();
            Instruction ins = insHandle.getInstruction();

            if(DEBUG) ByteCode.printOpCode(insHandle, cpg);

            if (!(ins instanceof InvokeInstruction))
                continue;
            InvokeInstruction invoke = (InvokeInstruction) ins;


            InjectionPoint injectionPoint = null;
            for (InjectionSource source : selectedSources) {
                injectionPoint = source.getInjectableParameters(invoke, cpg, insHandle);
                //System.out.println("Get param from = " + source.getClass().getSimpleName());
                if (injectionPoint != InjectionPoint.NONE) {
                    break;
                }
            }

            if (injectionPoint != InjectionPoint.NONE) {

                ConstantFrame frame = dataflow.getFactAtLocation(location);

                boolean constant = true;
                constant : for (int arg : injectionPoint.getInjectableArguments()) {
                    Constant value = frame.getStackValue(arg);

                    if (value == null || !value.isConstantString()) {
                        constant = false;
                        break constant;
                    }
                }

                boolean tainted = isTaintedValue(location,injectionPoint.getInjectableArguments(), cpg, cfg);
                if (!constant) {
                    BugInstance bugInstance = new BugInstance(this, injectionPoint.getBugType(),
                            tainted ? Priorities.HIGH_PRIORITY : Priorities.LOW_PRIORITY) //
                            .addClass(javaClass) //
                            .addMethod(javaClass, method) //
                            .addSourceLine(classContext, method, location);

                    if(injectionPoint.getInjectableMethod()!= null) {
                        bugInstance.addString(injectionPoint.getInjectableMethod());
                    }
                    bugReporter.reportBug(bugInstance);
                }

            }

        }

    }

    private InstructionHandle getPreviousInstruction(InstructionHandle handle, boolean skipNops) {
        while (handle.getPrev() != null) {
            handle = handle.getPrev();
            Instruction prevIns = handle.getInstruction();
            if (!(skipNops && prevIns instanceof NOP)) {
                return handle;
            }
        }
        return null;
    }

    private Location getPreviousLocation(CFG cfg, Location startLocation, boolean skipNops) {

        Location loc = startLocation;
        InstructionHandle prev = getPreviousInstruction(loc.getHandle(), skipNops);
        if (prev != null)
            return new Location(prev, loc.getBasicBlock());
        BasicBlock block = loc.getBasicBlock();
        while (true) {
            block = cfg.getPredecessorWithEdgeType(block, EdgeTypes.FALL_THROUGH_EDGE);
            if (block == null)
                return null;
            InstructionHandle lastInstruction = block.getLastInstruction();
            if (lastInstruction != null)
                return new Location(lastInstruction, block);
        }
    }

    private boolean isSafeValue(Location location, ConstantPoolGen cpg, CFG cfg) throws CFGBuilderException {
        Instruction prevIns = location.getHandle().getInstruction();
        if (prevIns instanceof LDC || prevIns instanceof GETSTATIC)
            return true;
        if (prevIns instanceof InvokeInstruction) {
            String methodName = ((InvokeInstruction) prevIns).getMethodName(cpg);
            if (methodName.startsWith("to") && methodName.endsWith("String") && methodName.length() > 8) {
                return true;
            }
        }
        if (prevIns instanceof AALOAD) {

            Location prev = getPreviousLocation(cfg, location, true);
            if (prev != null) {
                Location prev2 = getPreviousLocation(cfg, prev, true);
                if (prev2 != null && prev2.getHandle().getInstruction() instanceof GETSTATIC) {
                    GETSTATIC getStatic = (GETSTATIC) prev2.getHandle().getInstruction();
                    if (getStatic.getSignature(cpg).equals("[Ljava/lang/String;")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isTaintedValue(Location targetLocation, int[] arguments, ConstantPoolGen cpg, CFG cfg) {

        ByteCodeReplay replay = new ByteCodeReplay(cpg);

        if(DEBUG) {
            System.out.println("+++++++++++++++++++++++++++++++++++");
            System.out.println("Starting the replay");
            System.out.println("+++++++++++++++++++++++++++++++++++");
        }
        for (Location loc: cfg.orderedLocations() ) {
            InstructionHandle instructionHandle = loc.getHandle();
            instructionHandle.getInstruction();

            if(loc.getHandle().getPosition() == targetLocation.getHandle().getPosition()){

                return replay.isTaintedStackParameter(instructionHandle, arguments);
            }
            else {
                replay.exec(instructionHandle);
            }
        }

        System.err.println("Unable to reach the targeted location.");
        return true;
    }

    @Override
    public void report() {
    }

    /**
     *
     * @return
     */
    public abstract InjectionSource[] getInjectionSource();

}
