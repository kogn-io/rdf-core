/**
 * The implementation of the content-addressed IRI port: ni-rdf/1 as one procedure.
 *
 * <p>The public types are {@link io.kogn.rdf.cid.sexpr.ContentAddressedIRIGeneratorSexpr}, the
 * only implementation of {@link io.kogn.rdf.cid.ContentAddressedIRIGenerator}, and
 * {@link io.kogn.rdf.cid.sexpr.BaseRebaser}, which applies the renaming of ni-rdf/1 §6. Every
 * other type of this package is a package-private building block of the procedure of §1.3: the
 * preconditions (§3), the base mapping (§4.1, §4.2), the RDFC-1.0 labelling with its resource
 * limit (§4.3, §4.4), the encoding of the hashed input (§4.5), and the digest and name (§4.7,
 * §4.8). The base IRI of the described resource never reaches the hashed input: it is written
 * as a self placeholder, its fragment IRIs as their fragments, so the hashed input is the same
 * under any base IRI.</p>
 */
package io.kogn.rdf.cid.sexpr;
