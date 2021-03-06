/*
 * Copyright (c) 2008-2013, Matthias Mann
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *     * Redistributions of source code must retain the above copyright notice,
 *       this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Matthias Mann nor the names of its
 *       contributors may be used to endorse or promote products derived from
 *       this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package co.paralleluniverse.fibers.instrument;

import co.paralleluniverse.fibers.Instrumented;
import static co.paralleluniverse.fibers.instrument.Classes.ANNOTATION_DESC;
import static co.paralleluniverse.fibers.instrument.Classes.isYieldMethod;
import co.paralleluniverse.fibers.instrument.MethodDatabase.ClassEntry;
import co.paralleluniverse.fibers.instrument.MethodDatabase.SuspendableType;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

/**
 * Instrument a class by instrumenting all suspendable methods and copying the others.
 *
 * @author Matthias Mann
 * @author pron
 */
public class InstrumentClass extends ClassVisitor {
    static final String ALREADY_INSTRUMENTED_NAME = Type.getDescriptor(Instrumented.class);
    private final SuspendableClassifier classifier;
    
    private final MethodDatabase db;
    private boolean forceInstrumentation;
    private String className;
    private ClassEntry classEntry;
    private boolean alreadyInstrumented;
    private ArrayList<MethodNode> methods;

    public InstrumentClass(ClassVisitor cv, MethodDatabase db, boolean forceInstrumentation) {
        super(Opcodes.ASM4, cv);
        this.db = db;
        this.classifier = db.getClassifier();
        this.forceInstrumentation = forceInstrumentation;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.className = name;
        this.classEntry = db.getOrCreateClassEntry(className, superName);
        classEntry.setInterfaces(interfaces);

        forceInstrumentation |= classEntry.requiresInstrumentation();

        // need atleast 1.5 for annotations to work
        if (version < Opcodes.V1_5)
            version = Opcodes.V1_5;

// When Java allows adding interfaces in retransformation, we can mark the class with an interface, which makes checking whether it's instrumented faster (with instanceof)       
//        if(classEntry.requiresInstrumentation() && !contains(interfaces, SUSPENDABLE_NAME)) {
//            System.out.println("XX: Marking " + className + " as " + SUSPENDABLE_NAME);
//            interfaces = add(interfaces, SUSPENDABLE_NAME);
//        }

        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        if (desc.equals(InstrumentClass.ALREADY_INSTRUMENTED_NAME))
            alreadyInstrumented = true;

        return super.visitAnnotation(desc, visible);
    }

    @Override
    public MethodVisitor visitMethod(final int access, final String name, final String desc, final String signature, final String[] exceptions) {
        final SuspendableType markedSuspendable = classifier.isSuspendable(className, classEntry.getSuperName(), classEntry.getInterfaces(), name, desc, signature, exceptions);
        final SuspendableType setSuspendable = classEntry.check(name, desc);

        if (setSuspendable == null)
            classEntry.set(name, desc, markedSuspendable != null ? markedSuspendable : SuspendableType.NON_SUSPENDABLE);
   
        final boolean suspendable = markedSuspendable == SuspendableType.SUSPENDABLE | setSuspendable == SuspendableType.SUSPENDABLE;
     
        if (checkAccess(access) && !isYieldMethod(className, name)) {
            if (methods == null)
                methods = new ArrayList<MethodNode>();
            final MethodNode mn = new MethodNode(access, name, desc, signature, exceptions);

            if (suspendable) {
                if (db.isDebug())
                    db.log(LogLevel.INFO, "Method %s#%s suspendable: %s (markedSuspendable: %s setSuspendable: %s)", className, name, suspendable, markedSuspendable, setSuspendable);

                methods.add(mn);
                return mn; // this causes the mn to be initialized
            } else { // look for @Suspendable annotation
                return new MethodVisitor(Opcodes.ASM4, mn) {
                    private boolean susp = false;
                    private boolean commited = false;

                    @Override
                    public AnnotationVisitor visitAnnotation(String adesc, boolean visible) {
                        if (adesc.equals(ANNOTATION_DESC))
                            susp = true;
                        return super.visitAnnotation(adesc, visible);
                    }

                    @Override
                    public void visitCode() {
                        commit();
                        super.visitCode();
                    }

                    @Override
                    public void visitEnd() {
                        commit();
                        super.visitEnd();
                    }

                    private void commit() {
                        if (commited)
                            return;
                        commited = true;
                        if (db.isDebug())
                            db.log(LogLevel.INFO, "Method %s#%s suspendable: %s (markedSuspendable: %s setSuspendable: %s)", className, name, susp, susp, false);
                        classEntry.set(name, desc, susp ? SuspendableType.SUSPENDABLE : SuspendableType.NON_SUSPENDABLE);

                        if (susp)
                            methods.add(mn);
                        else {
                            MethodVisitor _mv = makeOutMV(mn);
                            _mv = new JSRInlinerAdapter(_mv, access, name, desc, signature, exceptions);
                            mn.accept(new MethodVisitor(Opcodes.ASM4, _mv) {
                                @Override
                                public void visitEnd() {
                                    // don't call visitEnd on MV
                                }
                            }); // write method as-is
                            this.mv = _mv;
                        }
                    }
                };
            }
        }
        return super.visitMethod(access, name, desc, signature, exceptions);
    }

    @Override
    @SuppressWarnings("CallToThreadDumpStack")
    public void visitEnd() {
        classEntry.setRequiresInstrumentation(false);
        db.recordSuspendableMethods(className, classEntry);

        if (methods != null && !methods.isEmpty()) {
            if (alreadyInstrumented && !forceInstrumentation) {
                for (MethodNode mn : methods)
                    mn.accept(makeOutMV(mn));
            } else {
                if (!alreadyInstrumented) {
                    super.visitAnnotation(ALREADY_INSTRUMENTED_NAME, true);
                    classEntry.setInstrumented(true);
                }

                for (MethodNode mn : methods) {
                    final MethodVisitor outMV = makeOutMV(mn);
                    try {
                        InstrumentMethod im = new InstrumentMethod(db, className, mn);
                        if (db.isDebug())
                            db.log(LogLevel.INFO, "About to instrument method %s#%s%s", className, mn.name, mn.desc);

                        if (im.collectCodeBlocks()) {
                            if (mn.name.charAt(0) == '<')
                                throw new UnableToInstrumentException("special method", className, mn.name, mn.desc);
                            im.accept(outMV, hasAnnotation(mn));
                        } else
                            mn.accept(outMV);
                    } catch (AnalyzerException ex) {
                        ex.printStackTrace();
                        throw new InternalError(ex.getMessage());
                    }
                }
            }
        } else {
            // if we don't have any suspendable methods, but our superclass is instrumented, we mark this class as instrumented, too.
            if (!alreadyInstrumented && classEntry.getSuperName() != null) {
                ClassEntry superClass = db.getClassEntry(classEntry.getSuperName());
                if (superClass != null && superClass.isInstrumented()) {
                    super.visitAnnotation(ALREADY_INSTRUMENTED_NAME, true);
                    classEntry.setInstrumented(true);
                }
            }
        }
        super.visitEnd();
    }

    private boolean hasAnnotation(MethodNode mn) {
        List<AnnotationNode> ans = (List<AnnotationNode>) mn.visibleAnnotations;
        if (ans == null)
            return false;
        for (AnnotationNode an : ans) {
            if (an.desc.equals(ANNOTATION_DESC))
                return true;
        }
        return false;
    }

    private MethodVisitor makeOutMV(MethodNode mn) {
        return super.visitMethod(mn.access, mn.name, mn.desc, mn.signature, toStringArray(mn.exceptions));
    }

    private static boolean checkAccess(int access) {
        return (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
    }

    private static String[] toStringArray(List<?> l) {
        if (l.isEmpty())
            return null;

        return l.toArray(new String[l.size()]);
    }
//    
//    private static boolean contains(String[] ifaces, String iface) {
//        for(String i : ifaces) {
//            if(i.equals(iface))
//                return true;
//        }
//        return false;
//    }
//    
//    private static String[] add(String[] ifaces, String iface) {
//        String[] newIfaces = Arrays.copyOf(ifaces, ifaces.length + 1);
//        newIfaces[newIfaces.length - 1] = iface;
//        return newIfaces;
//    }
}
