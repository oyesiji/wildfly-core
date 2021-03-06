/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.cli.handlers;

import java.util.List;

import org.jboss.as.cli.CommandFormatException;
import org.jboss.as.cli.CommandLineCompleter;
import org.jboss.as.cli.CommandContext;
import org.jboss.as.cli.impl.ArgumentWithValue;


/**
 *
 * @author Alexey Loubyansky
 */
public class EchoDMRHandler extends CommandHandlerWithHelp {

    public EchoDMRHandler() {
        super("echo-dmr");

        new ArgumentWithValue(this, new CommandLineCompleter() {
                @Override
                public int complete(CommandContext ctx, String buffer, int cursor, List<String> candidates) {

                    final String substituedLine = ctx.getParsedCommandLine().getSubstitutedLine();
                    boolean skipWS;
                    int wordCount;
                    if(Character.isWhitespace(substituedLine.charAt(0))) {
                        skipWS = true;
                        wordCount = 0;
                    } else {
                        skipWS = false;
                        wordCount = 1;
                    }
                    int cmdStart = 1;
                    while(cmdStart < substituedLine.length()) {
                        if(skipWS) {
                            if(!Character.isWhitespace(substituedLine.charAt(cmdStart))) {
                                skipWS = false;
                                ++wordCount;
                                if(wordCount == 2) {
                                    break;
                                }
                            }
                        } else if(Character.isWhitespace(substituedLine.charAt(cmdStart))) {
                            skipWS = true;
                        }
                        ++cmdStart;
                    }

                    final String cmd;
                    if(wordCount == 1) {
                        cmd = "";
                    } else if(wordCount != 2) {
                        return -1;
                    } else {
                        cmd = substituedLine.substring(cmdStart);
                    }

                    int cmdResult = ctx.getDefaultCommandCompleter().complete(ctx, cmd, cmd.length(), candidates);
                    if(cmdResult < 0) {
                        return cmdResult;
                    }

                    // escaping index correction
                    int escapeCorrection = 0;
                    int start = substituedLine.length() - 1 - buffer.length();
                    while(start - escapeCorrection >= 0) {
                        final char ch = substituedLine.charAt(start - escapeCorrection);
                        if(Character.isWhitespace(ch) || ch == '=') {
                            break;
                        }
                        ++escapeCorrection;
                    }

                    return buffer.length() + escapeCorrection - (cmd.length() - cmdResult);
                }}, Integer.MAX_VALUE, "--line") {
            };
    }

    @Override
    protected void recognizeArguments(CommandContext ctx) throws CommandFormatException {
        // allow arbitrary arguments, it's up to the command or operation handler to validate them
    }

    /* (non-Javadoc)
     * @see org.jboss.as.cli.handlers.CommandHandlerWithHelp#doHandle(org.jboss.as.cli.CommandContext)
     */
    @Override
    protected void doHandle(CommandContext ctx) throws CommandFormatException {
        String argsStr = ctx.getArgumentsString();
        if(argsStr == null) {
            throw new CommandFormatException("Missing the command or operation to translate to DMR.");
        }
        ctx.printLine(ctx.buildRequest(argsStr).toString());
    }
}
