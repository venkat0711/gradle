/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.nativeplatform.toolchain.internal.gcc

import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.work.WorkerLeaseService
import org.gradle.nativeplatform.internal.CompilerOutputFileNamingSchemeFactory
import org.gradle.nativeplatform.platform.internal.OperatingSystemInternal
import org.gradle.nativeplatform.toolchain.internal.ToolType
import org.gradle.nativeplatform.toolchain.internal.gcc.metadata.GccMetadata
import org.gradle.nativeplatform.toolchain.internal.metadata.CompilerMetaDataProvider
import org.gradle.nativeplatform.toolchain.internal.tools.CommandLineToolSearchResult
import org.gradle.nativeplatform.toolchain.internal.tools.DefaultGccCommandLineToolConfiguration
import org.gradle.nativeplatform.toolchain.internal.tools.ToolRegistry
import org.gradle.nativeplatform.toolchain.internal.tools.ToolSearchPath
import org.gradle.process.internal.ExecActionFactory
import spock.lang.Specification
import spock.lang.Unroll

class GccPlatformToolProviderTest extends Specification {

    def buildOperationExecuter = Mock(BuildOperationExecutor)
    def operatingSystem = Mock(OperatingSystemInternal)
    def toolSearchPath = Mock(ToolSearchPath)
    def toolRegistry = Mock(ToolRegistry)
    def execActionFactory = Mock(ExecActionFactory)
    def namingSchemeFactory = Mock(CompilerOutputFileNamingSchemeFactory)
    def workerLeaseService = Mock(WorkerLeaseService)
    CompilerMetaDataProvider<GccMetadata> metaDataProvider = Mock(CompilerMetaDataProvider)

    @Unroll
    def "arguments #args are passed to metadata provider for #toolType.toolName"() {
        def platformToolProvider = new GccPlatformToolProvider(buildOperationExecuter, operatingSystem, toolSearchPath, toolRegistry, execActionFactory, namingSchemeFactory, true, workerLeaseService, metaDataProvider)

        when:
        platformToolProvider.getSystemIncludes(toolType)

        then:
        1 * metaDataProvider.getCompilerMetaData(_, _) >> {
            assert arguments[1] == args
            Mock(GccMetadata)
        }
        1 * toolRegistry.getTool(toolType) >> new DefaultGccCommandLineToolConfiguration(toolType, 'exe')
        1 * toolSearchPath.locate(toolType, 'exe') >> Mock(CommandLineToolSearchResult)

        where:
        toolType                       | args
        ToolType.CPP_COMPILER          | ['-x', 'c++']
        ToolType.C_COMPILER            | ['-x', 'c']
        ToolType.OBJECTIVEC_COMPILER   | ['-x', 'objective-c']
        ToolType.OBJECTIVECPP_COMPILER | ['-x', 'objective-c++']
        ToolType.ASSEMBLER             | []
    }
}
