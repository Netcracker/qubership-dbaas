/*
Copyright 2026.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package controller

import (
	"go/ast"
	"go/parser"
	"go/token"
	"path/filepath"
	"testing"
)

// Every registered controller has to build its own limiter, or it shares the
// backoff state that TestRateLimiterConfigCreatesIndependentBackoffState keeps
// apart. The manager exposes no way to read a registered limiter back, so the
// wiring is asserted against the source instead.
func TestEveryControllerRegistrationBuildsItsOwnRateLimiter(t *testing.T) {
	sources, err := filepath.Glob("*_controller.go")
	if err != nil {
		t.Fatalf("failed to list controller sources: %v", err)
	}
	if len(sources) == 0 {
		t.Fatal("expected to find controller sources next to this test")
	}

	total := 0
	for _, source := range sources {
		file, err := parser.ParseFile(token.NewFileSet(), source, nil, 0)
		if err != nil {
			t.Fatalf("failed to parse %s: %v", source, err)
		}
		for _, declaration := range file.Decls {
			function, ok := declaration.(*ast.FuncDecl)
			if !ok || function.Name.Name != "SetupWithManager" || function.Body == nil {
				continue
			}
			registrations := countCalls(function.Body, "NewControllerManagedBy")
			limiters := countFreshLimiters(function.Body)
			if registrations == 0 {
				t.Errorf("%s: SetupWithManager registers no controller", source)
			}
			if limiters != registrations {
				t.Errorf("%s: %d controller registrations but %d controllerOptions() calls",
					source, registrations, limiters)
			}
			total += registrations
		}
	}
	if total == 0 {
		t.Fatal("expected at least one controller registration")
	}
}

// countCalls reports how many calls to the named method appear inside body.
func countCalls(body *ast.BlockStmt, method string) int {
	count := 0
	ast.Inspect(body, func(node ast.Node) bool {
		if isCallTo(node, method) {
			count++
		}
		return true
	})
	return count
}

// countFreshLimiters reports how many WithOptions calls take their options from
// controllerOptions, which constructs a new limiter on every call.
func countFreshLimiters(body *ast.BlockStmt) int {
	count := 0
	ast.Inspect(body, func(node ast.Node) bool {
		call, ok := node.(*ast.CallExpr)
		if !ok || !isCallTo(call, "WithOptions") || len(call.Args) != 1 {
			return true
		}
		if isCallTo(call.Args[0], "controllerOptions") {
			count++
		}
		return true
	})
	return count
}

func isCallTo(node ast.Node, method string) bool {
	call, ok := node.(*ast.CallExpr)
	if !ok {
		return false
	}
	selector, ok := call.Fun.(*ast.SelectorExpr)
	return ok && selector.Sel.Name == method
}
