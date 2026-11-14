package com.magnaboy.tools.cachediff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON parser that remembers where every value sits in the source text.
 * The offsets let {@link ModelIdRewriter} replace individual numbers without
 * re-serialising anything else.
 */
final class Json {
	private Json() {
	}

	static class Value {
		final int start;
		final int end;

		Value(int start, int end) {
			this.start = start;
			this.end = end;
		}
	}

	static final class Obj extends Value {
		final Map<String, Value> members;

		Obj(int start, int end, Map<String, Value> members) {
			super(start, end);
			this.members = Collections.unmodifiableMap(members);
		}

		Value get(String key) {
			return members.get(key);
		}

		String string(String key) {
			Value v = members.get(key);
			return v instanceof Str ? ((Str) v).value : null;
		}

		List<Value> array(String key) {
			Value v = members.get(key);
			return v instanceof Arr ? ((Arr) v).items : Collections.emptyList();
		}
	}

	static final class Arr extends Value {
		final List<Value> items;

		Arr(int start, int end, List<Value> items) {
			super(start, end);
			this.items = Collections.unmodifiableList(items);
		}
	}

	static final class Num extends Value {
		final String text;

		Num(int start, int end, String text) {
			super(start, end);
			this.text = text;
		}

		boolean isInt() {
			return text.matches("-?\\d+");
		}

		int intValue() {
			return Integer.parseInt(text);
		}
	}

	static final class Str extends Value {
		final String value;

		Str(int start, int end, String value) {
			super(start, end);
			this.value = value;
		}
	}

	static final class Literal extends Value {
		final String text;

		Literal(int start, int end, String text) {
			super(start, end);
			this.text = text;
		}
	}

	static final class ParseException extends Exception {
		ParseException(String message) {
			super(message);
		}
	}

	static Value parse(String text) throws ParseException {
		Parser p = new Parser(text);
		p.skipWhitespace();
		Value v = p.value();
		p.skipWhitespace();
		if (p.pos != text.length()) {
			throw p.error("unexpected trailing content");
		}
		return v;
	}

	/** Integers of an array value, or an empty array if the value is not an array of integers. */
	static int[] ints(List<Value> items) {
		int[] out = new int[items.size()];
		for (int i = 0; i < out.length; i++) {
			Value v = items.get(i);
			if (!(v instanceof Num) || !((Num) v).isInt()) {
				return new int[0];
			}
			out[i] = ((Num) v).intValue();
		}
		return out;
	}

	private static final class Parser {
		private final String s;
		private int pos;

		Parser(String s) {
			this.s = s;
		}

		ParseException error(String message) {
			int line = 1;
			for (int i = 0; i < pos && i < s.length(); i++) {
				if (s.charAt(i) == '\n') {
					line++;
				}
			}
			return new ParseException(message + " at line " + line);
		}

		void skipWhitespace() {
			while (pos < s.length()) {
				char c = s.charAt(pos);
				if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '﻿') {
					pos++;
				} else {
					break;
				}
			}
		}

		Value value() throws ParseException {
			if (pos >= s.length()) {
				throw error("unexpected end of input");
			}
			char c = s.charAt(pos);
			switch (c) {
				case '{':
					return object();
				case '[':
					return array();
				case '"':
					return string();
				case 't':
					return literal("true");
				case 'f':
					return literal("false");
				case 'n':
					return literal("null");
				default:
					if (c == '-' || (c >= '0' && c <= '9')) {
						return number();
					}
					throw error("unexpected character '" + c + "'");
			}
		}

		Obj object() throws ParseException {
			int start = pos++;
			Map<String, Value> members = new LinkedHashMap<>();
			skipWhitespace();
			if (peek() == '}') {
				pos++;
				return new Obj(start, pos, members);
			}
			while (true) {
				skipWhitespace();
				if (peek() != '"') {
					throw error("expected a key");
				}
				String key = string().value;
				skipWhitespace();
				expect(':');
				skipWhitespace();
				members.put(key, value());
				skipWhitespace();
				char c = peek();
				pos++;
				if (c == '}') {
					return new Obj(start, pos, members);
				}
				if (c != ',') {
					pos--;
					throw error("expected ',' or '}'");
				}
			}
		}

		Arr array() throws ParseException {
			int start = pos++;
			List<Value> items = new ArrayList<>();
			skipWhitespace();
			if (peek() == ']') {
				pos++;
				return new Arr(start, pos, items);
			}
			while (true) {
				skipWhitespace();
				items.add(value());
				skipWhitespace();
				char c = peek();
				pos++;
				if (c == ']') {
					return new Arr(start, pos, items);
				}
				if (c != ',') {
					pos--;
					throw error("expected ',' or ']'");
				}
			}
		}

		Str string() throws ParseException {
			int start = pos++;
			StringBuilder sb = new StringBuilder();
			while (true) {
				if (pos >= s.length()) {
					throw error("unterminated string");
				}
				char c = s.charAt(pos++);
				if (c == '"') {
					return new Str(start, pos, sb.toString());
				}
				if (c != '\\') {
					sb.append(c);
					continue;
				}
				if (pos >= s.length()) {
					throw error("unterminated string");
				}
				char e = s.charAt(pos++);
				switch (e) {
					case 'n':
						sb.append('\n');
						break;
					case 't':
						sb.append('\t');
						break;
					case 'r':
						sb.append('\r');
						break;
					case 'b':
						sb.append('\b');
						break;
					case 'f':
						sb.append('\f');
						break;
					case 'u':
						if (pos + 4 > s.length()) {
							throw error("bad unicode escape");
						}
						try {
							sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
						} catch (NumberFormatException ex) {
							throw error("bad unicode escape");
						}
						pos += 4;
						break;
					default:
						sb.append(e);
				}
			}
		}

		Num number() {
			int start = pos;
			while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
				pos++;
			}
			return new Num(start, pos, s.substring(start, pos));
		}

		Literal literal(String word) throws ParseException {
			if (!s.startsWith(word, pos)) {
				throw error("unexpected token");
			}
			int start = pos;
			pos += word.length();
			return new Literal(start, pos, word);
		}

		char peek() throws ParseException {
			if (pos >= s.length()) {
				throw error("unexpected end of input");
			}
			return s.charAt(pos);
		}

		void expect(char c) throws ParseException {
			if (peek() != c) {
				throw error("expected '" + c + "'");
			}
			pos++;
		}
	}
}
