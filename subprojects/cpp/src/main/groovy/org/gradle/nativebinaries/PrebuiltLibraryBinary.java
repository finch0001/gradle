/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.nativebinaries;

import java.io.File;

/**
 * The library binary for a {@link PrebuiltLibrary}.
 */
public interface PrebuiltLibraryBinary extends LibraryBinary {
    /**
     * Sets the output file.
     */
    // TODO:DAZ Rename this and make it specific to different library subtypes
    // TODO:DAZ Allow the base name to be specified, and determine .so/.dylib/etc from platform
    // TODO:DAZ Determine conventional mapping of variant dimensions to file names.
    void setOutputFile(File outputFile);
}
